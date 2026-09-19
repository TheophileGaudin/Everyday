package com.everyday.everyday_glasses.ui.components

interface ContextMenuHandler {
    fun submenuItemsFor(item: ContextMenu.MenuItem): List<ContextMenu.SubMenuItem>
    fun selectItem(item: ContextMenu.MenuItem)
    fun selectSubmenuItem(
        parent: ContextMenu.MenuItem,
        item: ContextMenu.SubMenuItem
    ): Boolean
    fun onMenuDismissed()
}

interface TextEditCommandHandler {
    fun cut()
    fun copy()
    fun paste(content: String)
    fun selectAll()
    fun showFormatting()
    fun onMenuDismissed()
}

interface TextFormattingHandler {
    fun setBold(enabled: Boolean)
    fun setItalic(enabled: Boolean)
    fun setUnderline(enabled: Boolean)
    fun setFontSize(size: Float)
    fun setTextWrap(enabled: Boolean)
    fun setBulletList(enabled: Boolean)
    fun setNumberedList(enabled: Boolean)
    fun setColumns(columns: Int)
    fun onMenuDismissed()
}

interface CloseConfirmationHandler {
    fun confirmClose()
    fun onPopupDismissed()
}

interface LayoutDeletionHandler {
    fun deleteLayout(name: String)
    fun onPopupDismissed()
}

interface LayoutNameHandler {
    fun submitLayoutName(name: String)
    fun onPromptDismissed()
}