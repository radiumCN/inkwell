package com.radium.inkwell.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 删列：`ALTER TABLE … DROP COLUMN` 要 SQLite 3.35 起，Android 13 自带的是 3.32（14 是 3.39）。
 *
 * minSdk 从 35 降到 33 之后，一个老库（库版本 ≤ 11）被云备份 / 换机恢复到 Android 13 上，迁移 11→12 跑到
 * DROP COLUMN 就是语法错：Room 迁移失败，App 每次启动都崩，出不去。新装的不受影响（直接按最新结构建表，不走迁移）。
 *
 * 老 SQLite 上照 SQLite 文档「其他表结构变更」的办法重建：照原表的列（去掉要删的）建新表 → 搬数据 → 删旧表 →
 * 改名 → 重建原来的索引。列的类型、NOT NULL、默认值、主键都从 `PRAGMA table_info` 原样抄 —— Room 迁移完
 * 会逐列核对表结构（连默认值的写法都比），差一点就当迁移失败。两条路结果一致，见 DropColumnsTest。
 *
 * @param nativeDrop 测试用：强制走某一条路（测试机上的 SQLite 都新，不强制的话重建那条永远测不到）
 */
internal fun SQLiteConnection.dropColumns(
    table: String,
    columns: Set<String>,
    nativeDrop: Boolean = supportsDropColumn(),
) {
    if (nativeDrop) {
        columns.forEach { execSQL("ALTER TABLE `$table` DROP COLUMN `$it`") }
        return
    }
    val keep = tableColumns(table).filter { it.name !in columns }
    // 索引得在删旧表之前抄下来（删表连索引一起删）。原生 DROP COLUMN 遇到被索引的列会直接报错，
    // 这里也不替人做主：用到被删列的索引不重建
    val indexes = prepare("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL").use { st ->
        st.bindText(1, table)
        buildList { while (st.step()) add(st.getText(0)) }
    }.filter { sql -> columns.none { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(sql) } }

    val primaryKey = keep.filter { it.pkPosition > 0 }.sortedBy { it.pkPosition }
    val definitions = keep.map { c ->
        buildString {
            append('`').append(c.name).append('`')
            if (c.type.isNotEmpty()) append(' ').append(c.type)
            if (c.notNull) append(" NOT NULL")
            c.defaultValue?.let { append(" DEFAULT ").append(it) }
        }
    } + listOfNotNull(
        primaryKey.takeIf { it.isNotEmpty() }?.joinToString(prefix = "PRIMARY KEY(", postfix = ")") { "`${it.name}`" },
    )
    val names = keep.joinToString { "`${it.name}`" }
    val rebuilt = "${table}_rebuild"
    execSQL("CREATE TABLE `$rebuilt` (${definitions.joinToString()})")
    execSQL("INSERT INTO `$rebuilt` ($names) SELECT $names FROM `$table`")
    execSQL("DROP TABLE `$table`")
    execSQL("ALTER TABLE `$rebuilt` RENAME TO `$table`")
    indexes.forEach { execSQL(it) }
}

/** 这台机器的 SQLite 有没有 `DROP COLUMN`（3.35.0 起） */
internal fun SQLiteConnection.supportsDropColumn(): Boolean {
    val version = prepare("SELECT sqlite_version()").use { st ->
        st.step()
        st.getText(0)
    }
    val (major, minor) = version.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
    return major > 3 || (major == 3 && minor >= DROP_COLUMN_MINOR)
}

internal data class TableColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    /** 建表时写的默认值原文（`''`、`0`）；没有为 null */
    val defaultValue: String?,
    /** 在主键里排第几（从 1 起）；不是主键为 0 */
    val pkPosition: Int,
)

internal fun SQLiteConnection.tableColumns(table: String): List<TableColumn> =
    prepare("PRAGMA table_info(`$table`)").use { st ->
        buildList {
            // cid, name, type, notnull, dflt_value, pk
            while (st.step()) {
                add(
                    TableColumn(
                        name = st.getText(1),
                        type = st.getText(2),
                        notNull = st.getLong(3) != 0L,
                        defaultValue = if (st.isNull(4)) null else st.getText(4),
                        pkPosition = st.getLong(5).toInt(),
                    ),
                )
            }
        }
    }

private const val DROP_COLUMN_MINOR = 35
