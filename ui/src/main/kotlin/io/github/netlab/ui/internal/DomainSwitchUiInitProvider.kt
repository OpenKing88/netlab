package io.github.netlab.ui.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 面板模块的零代码初始化入口：只负责把通知栏入口挂上。
 *
 * <p>核心能力（域名规则、抓包存储）的初始化在 core 模块自己的 provider 里，两者互不依赖。
 */
class DomainSwitchUiInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { DomainSwitchUiNotification.start(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
