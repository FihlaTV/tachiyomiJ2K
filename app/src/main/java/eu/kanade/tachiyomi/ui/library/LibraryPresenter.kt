package eu.kanade.tachiyomi.ui.library

import android.os.Bundle
import com.jakewharton.rxrelay.BehaviorRelay
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.migration.MigrationFlags
import eu.kanade.tachiyomi.util.combineLatest
import eu.kanade.tachiyomi.util.isNullOrUnsubscribed
import eu.kanade.tachiyomi.util.removeArticles
import eu.kanade.tachiyomi.util.syncChaptersWithSource
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_IGNORE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_INCLUDE
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Date

/**
 * Class containing library information.
 */
private data class Library(val categories: List<Category>, val mangaMap: LibraryMap)

/**
 * Typealias for the library manga, using the category as keys, and list of manga as values.
 */
private typealias LibraryMap = Map<Int, List<LibraryItem>>

/**
 * Presenter of [LibraryController].
 */
class LibraryPresenter(
        private val db: DatabaseHelper = Injekt.get(),
        private val preferences: PreferencesHelper = Injekt.get(),
        private val coverCache: CoverCache = Injekt.get(),
        private val sourceManager: SourceManager = Injekt.get(),
        private val downloadManager: DownloadManager = Injekt.get()
) : BasePresenter<LibraryController>() {

    private val context = preferences.context

    /**
     * Categories of the library.
     */
    var categories: List<Category> = emptyList()
        private set

    /**
     * Relay used to apply the UI filters to the last emission of the library.
     */
    private val filterTriggerRelay = BehaviorRelay.create(Unit)

    /**
     * Relay used to apply the UI update to the last emission of the library.
     */
    private val downloadTriggerRelay = BehaviorRelay.create(Unit)

    /**
     * Relay used to apply the selected sorting method to the last emission of the library.
     */
    private val sortTriggerRelay = BehaviorRelay.create(Unit)

    /**
     * Library subscription.
     */
    private var librarySubscription: Subscription? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        subscribeLibrary()
    }

    /**
     * Subscribes to library if needed.
     */
    fun subscribeLibrary() {
        if (librarySubscription.isNullOrUnsubscribed()) {
            librarySubscription = getLibraryObservable()
                    .combineLatest(downloadTriggerRelay.observeOn(Schedulers.io())) {
                        lib, _ -> lib.apply { setDownloadCount(mangaMap) }
                    }
                    .combineLatest(filterTriggerRelay.observeOn(Schedulers.io())) {
                        lib, _ -> lib.copy(mangaMap = applyFilters(lib.mangaMap))
                    }
                    .combineLatest(sortTriggerRelay.observeOn(Schedulers.io())) {
                        lib, _ -> lib.copy(mangaMap = applySort(lib.mangaMap))
                    }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeLatestCache({ view, (categories, mangaMap) ->
                        view.onNextLibraryUpdate(categories, mangaMap)
                    })
        }
    }

    /**
     * Applies library filters to the given map of manga.
     *
     * @param map the map to filter.
     */
    private fun applyFilters(map: LibraryMap): LibraryMap {
        val filterDownloaded = preferences.filterDownloaded().getOrDefault()

        val filterUnread = preferences.filterUnread().getOrDefault()

        val filterCompleted = preferences.filterCompleted().getOrDefault()

        val filterFn: (LibraryItem) -> Boolean = f@ { item ->
            // Filter when there isn't unread chapters.
            if (filterUnread == STATE_INCLUDE && item.manga.unread == 0) return@f false
            if (filterUnread == STATE_EXCLUDE && item.manga.unread > 0) return@f false

            if (filterCompleted == STATE_INCLUDE && item.manga.status != SManga.COMPLETED)
                return@f false
            if (filterCompleted == STATE_EXCLUDE && item.manga.status == SManga.COMPLETED)
                return@f false

            // Filter when there are no downloads.
            if (filterDownloaded != STATE_IGNORE) {
                val isDownloaded = when {
                    item.manga.source == LocalSource.ID -> true
                    item.downloadCount != -1 -> item.downloadCount > 0
                    else -> downloadManager.getDownloadCount(item.manga) > 0
                }
                return@f if (filterDownloaded == STATE_INCLUDE) isDownloaded else !isDownloaded
            }
            true
        }

        return map.mapValues { entry -> entry.value.filter(filterFn) }
    }

    /**
     * Sets downloaded chapter count to each manga.
     *
     * @param map the map of manga.
     */
    private fun setDownloadCount(map: LibraryMap) {
        if (!preferences.downloadBadge().getOrDefault()) {
            // Unset download count if the preference is not enabled.
            for ((_, itemList) in map) {
                for (item in itemList) {
                    item.downloadCount = -1
                }
            }
            return
        }

        for ((_, itemList) in map) {
            for (item in itemList) {
                item.downloadCount = downloadManager.getDownloadCount(item.manga)
            }
        }
    }

    /**
     * Applies library sorting to the given map of manga.
     *
     * @param map the map to sort.
     */
    private fun applySort(map: LibraryMap): LibraryMap {
        val sortingMode = preferences.librarySortingMode().getOrDefault()

        val lastReadManga by lazy {
            var counter = 0
            db.getLastReadManga().executeAsBlocking().associate { it.id!! to counter++ }
        }
        val totalChapterManga by lazy {
            var counter = 0
            db.getTotalChapterManga().executeAsBlocking().associate { it.id!! to counter++ }
        }

        val sortFn: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            when (sortingMode) {
                LibrarySort.ALPHA -> sortAlphabetical(i1, i2)
                LibrarySort.LAST_READ -> {
                    // Get index of manga, set equal to list if size unknown.
                    val manga1LastRead = lastReadManga[i1.manga.id!!] ?: lastReadManga.size
                    val manga2LastRead = lastReadManga[i2.manga.id!!] ?: lastReadManga.size
                    val mangaCompare = manga1LastRead.compareTo(manga2LastRead)
                    if (mangaCompare == 0) sortAlphabetical(i1, i2) else mangaCompare
                }
                LibrarySort.LAST_UPDATED -> i2.manga.last_update.compareTo(i1.manga.last_update)
                LibrarySort.UNREAD -> i1.manga.unread.compareTo(i2.manga.unread)
                LibrarySort.TOTAL -> {
                    val manga1TotalChapter = totalChapterManga[i1.manga.id!!] ?: 0
                    val mange2TotalChapter = totalChapterManga[i2.manga.id!!] ?: 0
                    val mangaCompare = manga1TotalChapter.compareTo(mange2TotalChapter)
                    if (mangaCompare == 0) sortAlphabetical(i1, i2) else mangaCompare
                }
                LibrarySort.DRAG_AND_DROP -> {
                    0
                }
                else -> sortAlphabetical(i1, i2)
            }
        }

        val comparator = if (preferences.librarySortingAscending().getOrDefault())
            Comparator(sortFn)
        else
            Collections.reverseOrder(sortFn)

        return map.mapValues { entry -> entry.value.sortedWith(comparator) }
    }

    private fun sortAlphabetical(i1: LibraryItem, i2: LibraryItem): Int {
        return if (preferences.removeArticles().getOrDefault())
            i1.manga.title.removeArticles().compareTo(i2.manga.title.removeArticles(), true)
        else i1.manga.title.compareTo(i2.manga.title, true)
    }

    /**
     * Get the categories and all its manga from the database.
     *
     * @return an observable of the categories and its manga.
     */
    private fun getLibraryObservable(): Observable<Library> {
        return Observable.combineLatest(getCategoriesObservable(), getLibraryMangasObservable()) { dbCategories, libraryManga ->
            val categories = if (libraryManga.containsKey(0)) arrayListOf(Category.createDefault()) + dbCategories
            else dbCategories

            this.categories = categories
            Library(categories, libraryManga)
        }
    }

    /**
     * Get the categories from the database.
     *
     * @return an observable of the categories.
     */
    private fun getCategoriesObservable(): Observable<List<Category>> {
        return db.getCategories().asRxObservable()
    }

    /**
     * Get the manga grouped by categories.
     *
     * @return an observable containing a map with the category id as key and a list of manga as the
     * value.
     */
    private fun getLibraryMangasObservable(): Observable<LibraryMap> {
        val libraryAsList = preferences.libraryAsList()
        return db.getLibraryMangas().asRxObservable()
                .map { list ->
                    list.map { LibraryItem(it, libraryAsList) }.groupBy { it.manga.category }
                }
    }

    /**
     * Requests the library to be filtered.
     */
    fun requestFilterUpdate() {
        filterTriggerRelay.call(Unit)
    }

    /**
     * Requests the library to have download badges added.
     */
    fun requestDownloadBadgesUpdate() {
        downloadTriggerRelay.call(Unit)
    }

    /**
     * Requests the library to be sorted.
     */
    fun requestSortUpdate() {
        sortTriggerRelay.call(Unit)
    }

    /**
     * Called when a manga is opened.
     */
    fun onOpenManga() {
        // Avoid further db updates for the library when it's not needed
        librarySubscription?.let { remove(it) }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas.toSet()
                .map { db.getCategoriesForManga(it).executeAsBlocking() }
                .reduce { set1: Iterable<Category>, set2 -> set1.intersect(set2) }
    }

    /**
     * Remove the selected manga from the library.
     *
     * @param mangas the list of manga to delete.
     * @param deleteChapters whether to also delete downloaded chapters.
     */
    fun removeMangaFromLibrary(mangas: List<Manga>) {
        // Create a set of the list
        val mangaToDelete = mangas.distinctBy { it.id }
        mangaToDelete.forEach { it.favorite = false }

        Observable.fromCallable { db.insertMangas(mangaToDelete).executeAsBlocking() }
                .onErrorResumeNext { Observable.empty() }
                .subscribeOn(Schedulers.io())
                .subscribe()
    }

    fun confirmDeletion(mangas: List<Manga>) {
        Observable.fromCallable {
            val mangaToDelete = mangas.distinctBy { it.id }
            mangaToDelete.forEach { manga ->
                coverCache.deleteFromCache(manga.thumbnail_url)
                val source = sourceManager.get(manga.source) as? HttpSource
                if (source != null)
                    downloadManager.deleteManga(manga, source)
            }
        }.subscribeOn(Schedulers.io()).subscribe()
    }

    fun addMangas(mangas: List<Manga>) {
        val mangaToAdd = mangas.distinctBy { it.id }
        mangaToAdd.forEach { it.favorite = true }

        Observable.fromCallable { db.insertMangas(mangaToAdd).executeAsBlocking() }
            .onErrorResumeNext { Observable.empty() }
            .subscribeOn(Schedulers.io())
            .subscribe()
        mangaToAdd.forEach { db.insertManga(it).executeAsBlocking() }
    }

    /**
     * Move the given list of manga to categories.
     *
     * @param categories the selected categories.
     * @param mangas the list of manga to move.
     */
    fun moveMangasToCategories(categories: List<Category>, mangas: List<Manga>) {
        val mc = ArrayList<MangaCategory>()

        for (manga in mangas) {
            for (cat in categories) {
                mc.add(MangaCategory.create(manga, cat))
            }
        }

        db.setMangaCategories(mc, mangas)
    }

    fun migrateManga(prevManga: Manga, manga: Manga, replace: Boolean) {
        val source = sourceManager.get(manga.source) ?: return

        //state = state.copy(isReplacingManga = true)

        Observable.defer { source.fetchChapterList(manga) }
            .onErrorReturn { emptyList() }
            .doOnNext { migrateMangaInternal(source, it, prevManga, manga, replace) }
            .onErrorReturn { emptyList() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            //.doOnUnsubscribe { state = state.copy(isReplacingManga = false) }
            .subscribe()
    }

    fun hideShowTitle(mangas: List<Manga>, hide: Boolean) {
        mangas.forEach { it.hide_title = hide }
        db.inTransaction {
            mangas.forEach {
                db.updateMangaHideTitle(it).executeAsBlocking()
            }
        }
    }

    private fun migrateMangaInternal(source: Source, sourceChapters: List<SChapter>,
        prevManga: Manga, manga: Manga, replace: Boolean) {

        val flags = preferences.migrateFlags().getOrDefault()
        val migrateChapters = MigrationFlags.hasChapters(flags)
        val migrateCategories = MigrationFlags.hasCategories(flags)
        val migrateTracks = MigrationFlags.hasTracks(flags)

        db.inTransaction {
            // Update chapters read
            if (migrateChapters) {
                try {
                    syncChaptersWithSource(db, sourceChapters, manga, source)
                } catch (e: Exception) {
                    // Worst case, chapters won't be synced
                }

                val prevMangaChapters = db.getChapters(prevManga).executeAsBlocking()
                val maxChapterRead =
                    prevMangaChapters.filter { it.read }.maxBy { it.chapter_number }?.chapter_number
                if (maxChapterRead != null) {
                    val dbChapters = db.getChapters(manga).executeAsBlocking()
                    for (chapter in dbChapters) {
                        if (chapter.isRecognizedNumber && chapter.chapter_number <= maxChapterRead) {
                            chapter.read = true
                        }
                    }
                    db.insertChapters(dbChapters).executeAsBlocking()
                }
            }
            // Update categories
            if (migrateCategories) {
                val categories = db.getCategoriesForManga(prevManga).executeAsBlocking()
                val mangaCategories = categories.map { MangaCategory.create(manga, it) }
                db.setMangaCategories(mangaCategories, listOf(manga))
            }
            // Update track
            if (migrateTracks) {
                val tracks = db.getTracks(prevManga).executeAsBlocking()
                for (track in tracks) {
                    track.id = null
                    track.manga_id = manga.id!!
                }
                db.insertTracks(tracks).executeAsBlocking()
            }
            // Update favorite status
            if (replace) {
                prevManga.favorite = false
                db.updateMangaFavorite(prevManga).executeAsBlocking()
            }
            manga.favorite = true
            db.updateMangaFavorite(manga).executeAsBlocking()

            // SearchPresenter#networkToLocalManga may have updated the manga title, so ensure db gets updated title
            db.updateMangaTitle(manga).executeAsBlocking()
        }
    }

    /**
     * Update cover with local file.
     *
     * @param inputStream the new cover.
     * @param manga the manga edited.
     * @return true if the cover is updated, false otherwise
     */
    @Throws(IOException::class)
    fun editCoverWithStream(inputStream: InputStream, manga: Manga): Boolean {
        if (manga.source == LocalSource.ID) {
            LocalSource.updateCover(context, manga, inputStream)
            return true
        }

        if (manga.thumbnail_url != null && manga.favorite) {
            coverCache.copyToCache(manga.thumbnail_url!!, inputStream)
            (manga as? MangaImpl)?.last_cover_fetch = Date().time
            return true
        }
        return false
    }

}
