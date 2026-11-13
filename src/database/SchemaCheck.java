package database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings the database up to {@code sql/twmsv113.sql} at startup: whatever the
 * file declares and the database lacks is created, and nothing else is touched.
 *
 * <p>{@code sql/twmsv113.sql} is the one and only SQL file in the tree. There is
 * no migrations directory any more: a new table, view or column is added to
 * that file, and every server that starts afterwards picks it up here. In
 * order:
 *
 * <ol>
 * <li>the database named in {@code tms.Url}, when the server answers "Unknown
 * database" - created {@code big5} like the file's own {@code CREATE DATABASE};
 * <li>every <b>table</b> that is missing, with its keys, auto-increment
 * counter, foreign keys and the rows the file inserts into it, by running the
 * file's own statements for that table in the order they appear;
 * <li>every <b>view</b> that is missing, with its {@code DEFINER} removed so
 * that it belongs to whichever account the server logs in as;
 * <li>every <b>column</b> that a table already in the database lacks, at the
 * position the file gives it. Indexes on that column are not added, and a
 * column that is {@code AUTO_INCREMENT} is reported and left alone - it cannot
 * be added without the key it needs.
 * </ol>
 *
 * <p>It never drops, renames, retypes or re-fills anything that exists, so a
 * table someone has filled with data of their own is safe whatever the file
 * says about it. That also means it cannot carry out a removal: dropping a
 * table that the file no longer declares is still a hand-run statement.
 *
 * <p>Runs from {@code ServerProperties}' static initialiser, after
 * {@code Settings.ini} is read and before {@code auth_server_channel_ip} is
 * queried - that query {@code System.exit}s when the table is missing, and the
 * table is one this class can create. Say nothing when nothing is missing;
 * otherwise one WARNING lists what was added.
 *
 * <p>The file is read with a parser that knows only what a dump needs - quotes,
 * backticks, the three comment styles and {@code ;} - and recognises four
 * statements: {@code CREATE TABLE}, {@code ALTER TABLE}, {@code INSERT INTO}
 * and {@code CREATE … VIEW}. Everything else ({@code SET}, {@code USE},
 * {@code CREATE DATABASE}, {@code DROP}, the {@code /*!40101 … *}{@code /}
 * blocks) is skipped. phpMyAdmin's stand-in {@code CREATE TABLE} for a view is
 * skipped too, so a missing view is created as a view.
 */
public final class SchemaCheck {

    private static final System.Logger LOG = System.getLogger(SchemaCheck.class.getName());

    /** Relative to the working directory, like {@code Settings.ini} and {@code Libs/}. */
    public static final String SCHEMA_FILE = "sql/twmsv113.sql";

    /** MySQL's ER_BAD_DB_ERROR, "Unknown database". */
    private static final int ER_BAD_DB_ERROR = 1049;

    private static final String NAME = "`?([A-Za-z0-9_$]+)`?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT = Pattern.compile(
            "^(?:INSERT|REPLACE)\\s+(?:IGNORE\\s+)?INTO\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_VIEW = Pattern.compile(
            "^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:ALGORITHM\\s*=\\s*\\w+\\s+)?(?:DEFINER\\s*=\\s*\\S+\\s+)?"
            + "(?:SQL\\s+SECURITY\\s+\\w+\\s+)?VIEW\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINER = Pattern.compile("DEFINER\\s*=\\s*\\S+\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_INCREMENT = Pattern.compile("\\bAUTO_INCREMENT\\b", Pattern.CASE_INSENSITIVE);

    private SchemaCheck() {
    }

    /** One column of a {@code CREATE TABLE}: its name and its whole definition, name included. */
    record Column(String name, String definition) {
    }

    /** A table the file declares: its {@code CREATE TABLE}, then every later statement aimed at it, in file order. */
    static final class Table {

        final String name;
        final String create;
        final List<Column> columns;
        final List<String> statements = new ArrayList<>();

        Table(final String name, final String create) {
            this.name = name;
            this.create = create;
            this.columns = columns(create);
        }
    }

    /** A view the file declares, with the statement that creates it ({@code DEFINER} already removed). */
    record View(String name, String create) {
    }

    /** Everything the file declares, keyed by lower-cased name, in file order. */
    static final class Schema {

        final Map<String, Table> tables = new LinkedHashMap<>();
        final Map<String, View> views = new LinkedHashMap<>();
    }

    /**
     * Compares the database with {@link #SCHEMA_FILE} and creates whatever is
     * missing. Never throws and never stops startup: a failure is logged, and
     * whatever really is missing then fails where it is used, exactly as it
     * would have without this check.
     */
    public static void ensure() {
        final Path file = Path.of(SCHEMA_FILE);
        if (!Files.isRegularFile(file)) {
            LOG.log(System.Logger.Level.WARNING, "[資料庫] 找不到 " + SCHEMA_FILE + "，略過資料表檢查。");
            return;
        }
        final Schema schema;
        try {
            schema = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "[資料庫] 無法讀取 " + SCHEMA_FILE + "，略過資料表檢查", e);
            return;
        }
        try (Connection con = connect()) {
            apply(con, schema);
        } catch (final SQLException e) {
            LOG.log(System.Logger.Level.ERROR, "[資料庫] 資料表檢查失敗", e);
        }
    }

    /** Connects to the configured database, creating the database first if the server says it does not exist. */
    private static Connection connect() throws SQLException {
        try {
            return DatabaseConnection.openUnpooled(true);
        } catch (final SQLException e) {
            if (e.getErrorCode() != ER_BAD_DB_ERROR) {
                throw e;
            }
        }
        final String name = DatabaseConnection.databaseName(server.ServerProperties.getProperty("tms.Url"));
        if (name == null || name.indexOf('`') >= 0) {
            throw new SQLException("tms.Url names no usable database: " + name);
        }
        try (Connection con = DatabaseConnection.openUnpooled(false);
             Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + name + "` DEFAULT CHARACTER SET big5 COLLATE big5_chinese_ci");
        }
        LOG.log(System.Logger.Level.WARNING, "[資料庫] 資料庫 " + name + " 不存在，已建立。");
        return DatabaseConnection.openUnpooled(true);
    }

    private static void apply(final Connection con, final Schema schema) throws SQLException {
        // What the database has. Lower-cased: with lower_case_table_names=1
        // (the Windows default) information_schema reports names lower-cased
        // whatever the CREATE said, and column names never were case-sensitive.
        final Map<String, String> existing = new HashMap<>();
        final Map<String, List<String>> existingColumns = new HashMap<>();
        try (Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE()")) {
                while (rs.next()) {
                    existing.put(lower(rs.getString(1)), rs.getString(2));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
                while (rs.next()) {
                    existingColumns.computeIfAbsent(lower(rs.getString(1)), k -> new ArrayList<>()).add(lower(rs.getString(2)));
                }
            }
        }

        final List<Table> missingTables = new ArrayList<>();
        final Map<Table, List<Column>> missingColumns = new LinkedHashMap<>();
        for (final Map.Entry<String, Table> e : schema.tables.entrySet()) {
            if (!existing.containsKey(e.getKey())) {
                missingTables.add(e.getValue());
                continue;
            }
            if (!"BASE TABLE".equalsIgnoreCase(existing.get(e.getKey()))) {
                continue;
            }
            final Set<String> have = new HashSet<>(existingColumns.getOrDefault(e.getKey(), List.of()));
            final List<Column> lacking = new ArrayList<>();
            for (final Column c : e.getValue().columns) {
                if (!have.contains(lower(c.name()))) {
                    lacking.add(c);
                }
            }
            if (!lacking.isEmpty()) {
                missingColumns.put(e.getValue(), lacking);
            }
        }
        final List<View> missingViews = new ArrayList<>();
        for (final Map.Entry<String, View> e : schema.views.entrySet()) {
            if (!existing.containsKey(e.getKey())) {
                missingViews.add(e.getValue());
            }
        }
        if (missingTables.isEmpty() && missingColumns.isEmpty() && missingViews.isEmpty()) {
            return;
        }

        final List<String> done = new ArrayList<>();
        try (Statement st = con.createStatement()) {
            // The file's own SET SQL_MODE / time_zone, plus foreign key checks
            // off so that tables can be created in file order whatever they
            // reference. This connection is not pooled, so none of it outlives
            // the check.
            st.execute("SET SESSION sql_mode = 'NO_AUTO_VALUE_ON_ZERO'");
            st.execute("SET SESSION time_zone = '+00:00'");
            st.execute("SET SESSION foreign_key_checks = 0");

            for (final Table t : missingTables) {
                long rows = 0;
                boolean created = false;
                try {
                    st.execute(t.create);
                    created = true;
                    for (final String sql : t.statements) {
                        st.execute(sql);
                        final int count = st.getUpdateCount();
                        if (count > 0 && INSERT.matcher(sql).find()) {
                            rows += count;
                        }
                    }
                    done.add("資料表 " + t.name + "（" + rows + " 筆資料）");
                } catch (final SQLException e) {
                    // A half-built table would pass the existence check on every
                    // later start and never be completed, so drop what this run
                    // created - and only that - and let the next start retry.
                    if (created) {
                        try {
                            st.execute("DROP TABLE IF EXISTS `" + t.name + "`");
                        } catch (final SQLException ignored) {
                            // Reported below either way.
                        }
                    }
                    LOG.log(System.Logger.Level.ERROR, "[資料庫] 無法建立資料表 " + t.name, e);
                }
            }
            for (final View v : missingViews) {
                try {
                    st.execute(v.create());
                    done.add("檢視表 " + v.name());
                } catch (final SQLException e) {
                    LOG.log(System.Logger.Level.ERROR, "[資料庫] 無法建立檢視表 " + v.name(), e);
                }
            }
            for (final Map.Entry<Table, List<Column>> e : missingColumns.entrySet()) {
                final Table t = e.getKey();
                for (final Column c : e.getValue()) {
                    if (AUTO_INCREMENT.matcher(c.definition()).find()) {
                        LOG.log(System.Logger.Level.WARNING, "[資料庫] " + t.name + "." + c.name()
                                + " 是 AUTO_INCREMENT 欄位，無法自動補上，請手動處理。");
                        continue;
                    }
                    final String sql = "ALTER TABLE `" + t.name + "` ADD COLUMN " + c.definition() + position(t, c);
                    try {
                        st.execute(sql);
                        done.add("欄位 " + t.name + "." + c.name());
                    } catch (final SQLException ex) {
                        LOG.log(System.Logger.Level.ERROR, "[資料庫] 無法補上欄位 " + t.name + "." + c.name(), ex);
                    }
                }
            }
        }
        if (!done.isEmpty()) {
            final String nl = System.lineSeparator();
            final StringBuilder sb = new StringBuilder("[資料庫] 依 ").append(SCHEMA_FILE).append(" 補上了 ")
                    .append(done.size()).append(" 項：");
            for (final String d : done) {
                sb.append(nl).append("      ").append(d);
            }
            LOG.log(System.Logger.Level.WARNING, sb.toString());
        }
    }

    /** {@code AFTER `previous`}, or {@code FIRST}, so a column lands where the file puts it. */
    static String position(final Table t, final Column c) {
        final int i = t.columns.indexOf(c);
        return i <= 0 ? " FIRST" : " AFTER `" + t.columns.get(i - 1).name() + "`";
    }

    private static String lower(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Parsing. Pure functions over the file's text, so they are unit-tested.
    // ------------------------------------------------------------------

    /** Reads what a dump declares. See the class javadoc for which statements count. */
    static Schema parse(final String text) {
        final Schema schema = new Schema();
        final List<String> statements = splitStatements(text);
        // Views first: phpMyAdmin writes a stand-in CREATE TABLE for each view
        // long before the view itself, and that stand-in must not be taken for
        // a real table.
        for (final String sql : statements) {
            final Matcher m = CREATE_VIEW.matcher(sql);
            if (m.find()) {
                schema.views.put(lower(m.group(1)), new View(m.group(1), DEFINER.matcher(sql).replaceFirst("")));
            }
        }
        for (final String sql : statements) {
            Matcher m = CREATE_TABLE.matcher(sql);
            if (m.find()) {
                final String key = lower(m.group(1));
                if (!schema.views.containsKey(key) && !schema.tables.containsKey(key)) {
                    schema.tables.put(key, new Table(m.group(1), sql));
                }
                continue;
            }
            m = ALTER_TABLE.matcher(sql);
            if (!m.find()) {
                m = INSERT.matcher(sql);
                if (!m.find()) {
                    continue;
                }
            }
            final Table t = schema.tables.get(lower(m.group(1)));
            if (t != null) {
                t.statements.add(sql);
            }
        }
        return schema;
    }

    /**
     * Splits SQL text into statements on {@code ;}, ignoring the ones inside
     * quotes and backticks, and drops comments: {@code -- } and {@code #} to
     * the end of the line, and {@code /* … *}{@code /} (which takes MySQL's
     * {@code /*!40101 … *}{@code /} version comments with it - in a dump those
     * are only ever client {@code SET}s). Blank statements are left out.
     */
    static List<String> splitStatements(final String text) {
        final List<String> out = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        final int n = text.length();
        int i = 0;
        while (i < n) {
            final char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                final int end = endOfQuoted(text, i);
                sb.append(text, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-'
                    && (i + 2 == n || Character.isWhitespace(text.charAt(i + 2)))) {
                i = endOfLine(text, i);
            } else if (c == '#') {
                i = endOfLine(text, i);
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                final int close = text.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                sb.append(' ');
            } else if (c == ';') {
                add(out, sb);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        add(out, sb);
        return out;
    }

    private static void add(final List<String> out, final StringBuilder sb) {
        final String s = sb.toString().strip();
        if (!s.isEmpty()) {
            out.add(s);
        }
        sb.setLength(0);
    }

    /** Index of the line break that ends the comment starting at {@code i} (kept, so lines stay apart). */
    private static int endOfLine(final String text, final int i) {
        final int nl = text.indexOf('\n', i);
        return nl < 0 ? text.length() : nl;
    }

    /**
     * Index just past the quoted run that starts at {@code start}. A doubled
     * quote stays inside; so does a backslash-escaped one, except in
     * backticks, where MySQL has no backslash escapes.
     */
    private static int endOfQuoted(final String text, final int start) {
        final char q = text.charAt(start);
        final int n = text.length();
        int i = start + 1;
        while (i < n) {
            final char c = text.charAt(i);
            if (c == '\\' && q != '`') {
                i += 2;
            } else if (c == q) {
                if (i + 1 < n && text.charAt(i + 1) == q) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return n;
    }

    /**
     * The columns of a {@code CREATE TABLE}, in order: every top-level entry
     * of its parenthesised body that starts with a backtick-quoted name. Keys
     * and constraints written inline are not columns and are left out.
     */
    static List<Column> columns(final String create) {
        final List<Column> out = new ArrayList<>();
        final int open = create.indexOf('(');
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int partStart = open + 1;
        final int n = create.length();
        int i = open;
        while (i < n) {
            final char c = create.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = endOfQuoted(create, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    column(out, create.substring(partStart, i));
                    break;
                }
            } else if (c == ',' && depth == 1) {
                column(out, create.substring(partStart, i));
                partStart = i + 1;
            }
            i++;
        }
        return out;
    }

    private static void column(final List<Column> out, final String part) {
        final String def = part.strip();
        if (def.startsWith("`")) {
            final int end = def.indexOf('`', 1);
            if (end > 1) {
                out.add(new Column(def.substring(1, end), def));
            }
        }
    }
}
