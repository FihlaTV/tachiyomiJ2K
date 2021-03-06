package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.util.AttributeSet
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.catalogue.filter.TriStateItem
import eu.kanade.tachiyomi.widget.ExtendedNavigationView
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.MultiSort.Companion.SORT_ASC
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.MultiSort.Companion.SORT_DESC
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.MultiSort.Companion.SORT_NONE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_IGNORE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_EXCLUDE
import uy.kohesive.injekt.injectLazy

/**
 * The navigation view shown in a drawer with the different options to show the library.
 */
class LibraryNavigationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : ExtendedNavigationView(context, attrs) {

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * List of groups shown in the view.
     */
    private val groups = listOf(FilterGroup(), SortGroup(), DisplayGroup(), BadgeGroup())

    /**
     * Adapter instance.
     */
    private val adapter = Adapter(groups.map { it.createItems() }.flatten())

    /**
     * Click listener to notify the parent fragment when an item from a group is clicked.
     */
    var onGroupClicked: (Group) -> Unit = {}

    init {
        recycler.adapter = adapter
        addView(recycler)

        groups.forEach { it.initModels() }
    }

    /**
     * Returns true if there's at least one filter from [FilterGroup] active.
     */
    fun hasActiveFilters(): Boolean {
        return (groups[0] as FilterGroup).items.any { it.state != STATE_IGNORE }
    }

    /**
     * Adapter of the recycler view.
     */
    inner class Adapter(items: List<Item>) : ExtendedNavigationView.Adapter(items) {

        override fun onItemClicked(item: Item) {
            if (item is GroupedItem) {
                item.group.onItemClicked(item)
                onGroupClicked(item.group)
            }
        }
    }

    /**
     * Filters group (unread, downloaded, ...).
     */
    inner class FilterGroup : Group {

        private val downloaded = Item.TriStateGroup(R.string.action_filter_downloaded, this)

        private val unread = Item.TriStateGroup(R.string.action_filter_unread, this)

        private val completed = Item.TriStateGroup(R.string.completed, this)

        override val items = listOf(downloaded, unread, completed)

        override val header = Item.Header(R.string.action_filter)

        override val footer = Item.Separator()

        override fun initModels() {
            try {
                downloaded.state = preferences.filterDownloaded().getOrDefault()
                unread.state = preferences.filterUnread().getOrDefault()
                completed.state = preferences.filterCompleted().getOrDefault()
            }
            catch (e: Exception) {
                preferences.upgradeFilters()
            }
        }

        override fun onItemClicked(item: Item) {
            item as Item.TriStateGroup
            val newState = when (item.state) {
                STATE_IGNORE -> STATE_INCLUDE
                STATE_INCLUDE -> STATE_EXCLUDE
                else -> STATE_IGNORE
            }
            item.state = newState
            when (item) {
                downloaded -> preferences.filterDownloaded().set(item.state)
                unread -> preferences.filterUnread().set(item.state)
                completed -> preferences.filterCompleted().set(item.state)
            }

            adapter.notifyItemChanged(item)
        }
    }

    /**
     * Sorting group (alphabetically, by last read, ...) and ascending or descending.
     */
    inner class SortGroup : Group {

        private val alphabetically = Item.MultiSort(R.string.action_sort_alpha, this)

        private val total = Item.MultiSort(R.string.action_sort_total, this)

        private val lastRead = Item.MultiSort(R.string.action_sort_last_read, this)

        private val lastUpdated = Item.MultiSort(R.string.action_sort_last_updated, this)

        private val unread = Item.MultiSort(R.string.action_filter_unread, this)

        private val dragAndDrop = Item.MultiSort(R.string.action_sort_drag_and_drop, this)

        override val items = listOf(alphabetically, lastRead, lastUpdated, unread, total, 
            dragAndDrop)

        override val header = Item.Header(R.string.action_sort)

        override val footer = Item.Separator()

        override fun initModels() {
            val sorting = preferences.librarySortingMode().getOrDefault()
            val order = if (preferences.librarySortingAscending().getOrDefault())
                SORT_ASC else SORT_DESC

            alphabetically.state = if (sorting == LibrarySort.ALPHA) order else SORT_NONE
            lastRead.state = if (sorting == LibrarySort.LAST_READ) order else SORT_NONE
            lastUpdated.state = if (sorting == LibrarySort.LAST_UPDATED) order else SORT_NONE
            unread.state = if (sorting == LibrarySort.UNREAD) order else SORT_NONE
            total.state = if (sorting == LibrarySort.TOTAL) order else SORT_NONE
            dragAndDrop.state = if (sorting == LibrarySort.DRAG_AND_DROP) order else SORT_NONE
        }

        override fun onItemClicked(item: Item) {
            item as Item.MultiStateGroup
            val prevState = item.state

            item.group.items.forEach { (it as Item.MultiStateGroup).state = SORT_NONE }
            if (item == dragAndDrop)
                item.state = SORT_ASC
            else
                item.state = when (prevState) {
                    SORT_NONE -> SORT_ASC
                    SORT_ASC -> SORT_DESC
                    SORT_DESC -> SORT_ASC
                    else -> throw Exception("Unknown state")
                }

            preferences.librarySortingMode().set(when (item) {
                alphabetically -> LibrarySort.ALPHA
                lastRead -> LibrarySort.LAST_READ
                lastUpdated -> LibrarySort.LAST_UPDATED
                unread -> LibrarySort.UNREAD
                total -> LibrarySort.TOTAL
                dragAndDrop -> LibrarySort.DRAG_AND_DROP
                else -> LibrarySort.ALPHA
            })
            preferences.librarySortingAscending().set(item.state == SORT_ASC)

            item.group.items.forEach { adapter.notifyItemChanged(it) }
        }

    }

    inner class BadgeGroup : Group {
        private val downloadBadge = Item.CheckboxGroup(R.string.action_display_download_badge, this)
        override val header = null
        override val footer = null
        override val items = listOf(downloadBadge)
        override fun initModels() {
            downloadBadge.checked = preferences.downloadBadge().getOrDefault()
        }

        override fun onItemClicked(item: Item) {
            item as Item.CheckboxGroup
            item.checked = !item.checked
            preferences.downloadBadge().set((item.checked))
            adapter.notifyItemChanged(item)
        }
    }

    /**
     * Display group, to show the library as a list or a grid.
     */
    inner class DisplayGroup : Group {

        private val grid = Item.Radio(R.string.action_display_grid, this)

        private val list = Item.Radio(R.string.action_display_list, this)

        override val items = listOf(grid, list)

        override val header = Item.Header(R.string.action_display)

        override val footer = null

        override fun initModels() {
            val asList = preferences.libraryAsList().getOrDefault()
            grid.checked = !asList
            list.checked = asList
        }

        override fun onItemClicked(item: Item) {
            item as Item.Radio
            if (item.checked) return

            item.group.items.forEach { (it as Item.Radio).checked = false }
            item.checked = true

            preferences.libraryAsList().set(item == list)

            item.group.items.forEach { adapter.notifyItemChanged(it) }
        }
    }
}
