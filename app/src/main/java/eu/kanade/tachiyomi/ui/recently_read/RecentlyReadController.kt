package eu.kanade.tachiyomi.ui.recently_read

import androidx.recyclerview.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.catalogue.browse.ProgressItem
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.RecyclerWindowInsetsListener
import eu.kanade.tachiyomi.util.toast
import kotlinx.android.synthetic.main.recently_read_controller.*

/**
 * Fragment that shows recently read manga.
 * Uses R.layout.fragment_recently_read.
 * UI related actions should be called from here.
 */
class RecentlyReadController : NucleusController<RecentlyReadPresenter>(),
        FlexibleAdapter.OnUpdateListener,
        FlexibleAdapter.EndlessScrollListener,
        RecentlyReadAdapter.OnRemoveClickListener,
        RecentlyReadAdapter.OnResumeClickListener,
        RecentlyReadAdapter.OnCoverClickListener,
        RemoveHistoryDialog.Listener {

    /**
     * Adapter containing the recent manga.
     */
    var adapter: RecentlyReadAdapter? = null
        private set

    /**
     * Endless loading item.
     */
    private var progressItem: ProgressItem? = null

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_recent_manga)
    }

    override fun createPresenter(): RecentlyReadPresenter {
        return RecentlyReadPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.recently_read_controller, container, false)
    }

    /**
     * Called when view is created
     *
     * @param view created view
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        // Initialize adapter
        recycler.layoutManager = LinearLayoutManager(view.context)
        adapter = RecentlyReadAdapter(this@RecentlyReadController)
        recycler.setHasFixedSize(true)
        recycler.adapter = adapter
        recycler.setOnApplyWindowInsetsListener(RecyclerWindowInsetsListener)
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    /**
     * Populate adapter with chapters
     *
     * @param mangaHistory list of manga history
     */
    fun onNextManga(mangaHistory: List<RecentlyReadItem>, cleanBatch: Boolean = false) {
        if (adapter?.itemCount ?: 0 == 0 || cleanBatch)
            resetProgressItem()
        if (cleanBatch) adapter?.updateDataSet(mangaHistory)
        else adapter?.onLoadMoreComplete(mangaHistory)
    }

    fun onAddPageError(error: Throwable) {
        adapter?.onLoadMoreComplete(null)
        adapter?.endlessTargetCount = 1
    }

    override fun onUpdateEmptyView(size: Int) {
        if (size > 0) {
            empty_view.hide()
        } else {
            empty_view.show(R.drawable.ic_glasses_black_128dp, R.string.information_no_recent_manga)
        }
    }

    /**
     * Sets a new progress item and reenables the scroll listener.
     */
    private fun resetProgressItem() {
        progressItem = ProgressItem()
        adapter?.endlessTargetCount = 0
        adapter?.setEndlessScrollListener(this, progressItem!!)
    }

    override fun onLoadMore(lastPosition: Int, currentPage: Int) {
        val view = view ?: return
        if (BackupRestoreService.isRunning(view.context.applicationContext)) {
            onAddPageError(Throwable())
            return
        }
        val adapter = adapter ?: return
        presenter.requestNext(adapter.itemCount)
    }

    override fun noMoreLoad(newItemsSize: Int) { }

    override fun onResumeClick(position: Int) {
        val activity = activity ?: return
        val (manga, chapter, _) = (adapter?.getItem(position) as? RecentlyReadItem)?.mch ?: return

        val nextChapter = presenter.getNextChapter(chapter, manga)
        if (nextChapter != null) {
            val intent = ReaderActivity.newIntent(activity, manga, nextChapter)
            startActivity(intent)
        } else {
            activity.toast(R.string.no_next_chapter)
        }
    }

    override fun onRemoveClick(position: Int) {
        val (manga, _, history) = (adapter?.getItem(position) as? RecentlyReadItem)?.mch ?: return
        RemoveHistoryDialog(this, manga, history).showDialog(router)
    }

    override fun onCoverClick(position: Int, lastTouchY: Float) {
        val manga = (adapter?.getItem(position) as? RecentlyReadItem)?.mch?.manga ?: return
        router.pushController(MangaController(manga, lastTouchY).withFadeTransaction())
    }

    override fun removeHistory(manga: Manga, history: History, all: Boolean) {
        presenter.lastCount = adapter?.itemCount ?: 25
        if (all) {
            // Reset last read of chapter to 0L
            presenter.removeAllFromHistory(manga.id!!)
        } else {
            // Remove all chapters belonging to manga from library
            presenter.removeFromHistory(history)
        }
    }

}
