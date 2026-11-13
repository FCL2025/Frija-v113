package database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parsing half of the startup schema check: reading {@code sql/twmsv113.sql}
 * into tables, views and columns. Applying it needs a database and is checked
 * by starting the server against one.
 */
class SchemaCheckTest {

    /** The shapes phpMyAdmin writes, cut down: keys and auto-increment in later ALTERs, a view with a stand-in table. */
    private static final String DUMP = """
            -- header comment; with a semicolon
            SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
            /*!40101 SET NAMES utf8mb4 */;
            CREATE DATABASE IF NOT EXISTS `twmsv113` DEFAULT CHARACTER SET big5;
            USE `twmsv113`;

            CREATE TABLE `accounts` (
              `id` int(11) NOT NULL,
              -- a comment inside the body, with a , comma
              `name` varchar(30) NOT NULL DEFAULT 'a,b;c',
              `money` decimal(10,2) DEFAULT NULL,
              `kind` enum('x','y') NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=big5;

            INSERT INTO `accounts` (`id`, `name`) VALUES (1, 'it''s; -- not a comment'), (2, 'back\\'slash;');

            CREATE TABLE `readable_log` (
            `accountname` varchar(30)
            ,`count` int(11)
            );

            CREATE TABLE `spawns` (
              `id` int NOT NULL AUTO_INCREMENT,
              `idd` int NOT NULL,
              PRIMARY KEY (`id`),
              KEY `idd` (`idd`)
            ) ENGINE=InnoDB DEFAULT CHARSET=big5;

            DROP TABLE IF EXISTS `readable_log`;
            CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`localhost` SQL SECURITY DEFINER VIEW `readable_log`  AS  select `a`.`name` AS `accountname` from `accounts` `a` ;

            ALTER TABLE `accounts`
              ADD PRIMARY KEY (`id`);
            ALTER TABLE `accounts`
              MODIFY `id` int(11) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=5;
            ALTER TABLE `nosuchtable` ADD KEY `k` (`k`);
            INSERT INTO cashshop VALUES (1);
            INSERT INTO spawns (idd) VALUES (7);
            """;

    @Test
    @DisplayName("splitStatements ignores ; inside quotes and comments and drops the comments")
    void split() {
        final List<String> s = SchemaCheck.splitStatements(
                "SELECT 'a;b'; -- c;d\nSELECT \"e;f\" /* g;h */ ; SELECT `i;j`;# k;l\nSELECT 'm\\';n'");
        assertEquals(List.of("SELECT 'a;b'", "SELECT \"e;f\"", "SELECT `i;j`", "SELECT 'm\\';n'"), s);
    }

    @Test
    @DisplayName("-- only starts a comment when followed by whitespace, as in MySQL")
    void doubleDash() {
        assertEquals(List.of("SELECT 1--1"), SchemaCheck.splitStatements("SELECT 1--1;"));
        assertEquals(List.of("SELECT 1"), SchemaCheck.splitStatements("SELECT 1 --"));
    }

    @Test
    @DisplayName("tables come out in file order; a view's stand-in CREATE TABLE is not a table")
    void tablesAndViews() {
        final SchemaCheck.Schema schema = SchemaCheck.parse(DUMP);
        assertEquals(List.of("accounts", "spawns"), List.copyOf(schema.tables.keySet()));
        assertEquals(List.of("readable_log"), List.copyOf(schema.views.keySet()));
    }

    @Test
    @DisplayName("a view loses its DEFINER, so it belongs to whoever the server logs in as")
    void viewDefiner() {
        final String create = SchemaCheck.parse(DUMP).views.get("readable_log").create();
        assertFalse(create.contains("DEFINER="), create);
        assertFalse(create.contains("root"), create);
        assertTrue(create.startsWith("CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `readable_log`"), create);
    }

    @Test
    @DisplayName("every ALTER and INSERT aimed at a table follows it, in file order; others are dropped")
    void statementsPerTable() {
        final SchemaCheck.Schema schema = SchemaCheck.parse(DUMP);
        final List<String> accounts = schema.tables.get("accounts").statements;
        assertEquals(3, accounts.size());
        assertTrue(accounts.get(0).startsWith("INSERT INTO `accounts`"));
        assertTrue(accounts.get(0).contains("'it''s; -- not a comment'"), accounts.get(0));
        assertTrue(accounts.get(0).contains("'back\\'slash;'"), accounts.get(0));
        assertTrue(accounts.get(1).contains("ADD PRIMARY KEY"));
        assertTrue(accounts.get(2).contains("AUTO_INCREMENT=5"));
        assertEquals(List.of("INSERT INTO spawns (idd) VALUES (7)"), schema.tables.get("spawns").statements);
    }

    @Test
    @DisplayName("columns: commas inside types, defaults and comments do not split; inline keys are not columns")
    void columns() {
        final SchemaCheck.Schema schema = SchemaCheck.parse(DUMP);
        final List<SchemaCheck.Column> accounts = schema.tables.get("accounts").columns;
        assertEquals(List.of("id", "name", "money", "kind"), accounts.stream().map(SchemaCheck.Column::name).toList());
        assertEquals("`name` varchar(30) NOT NULL DEFAULT 'a,b;c'", accounts.get(1).definition());
        assertEquals("`money` decimal(10,2) DEFAULT NULL", accounts.get(2).definition());
        assertEquals("`kind` enum('x','y') NOT NULL", accounts.get(3).definition());
        assertEquals(List.of("id", "idd"),
                schema.tables.get("spawns").columns.stream().map(SchemaCheck.Column::name).toList());
    }

    @Test
    @DisplayName("an added column goes where the file puts it")
    void position() {
        final SchemaCheck.Table t = SchemaCheck.parse(DUMP).tables.get("accounts");
        assertEquals(" FIRST", SchemaCheck.position(t, t.columns.get(0)));
        assertEquals(" AFTER `name`", SchemaCheck.position(t, t.columns.get(2)));
    }

    @Test
    @DisplayName("table names match case-insensitively, as information_schema reports them on Windows")
    void caseInsensitive() {
        final SchemaCheck.Schema schema = SchemaCheck.parse("create table `MixedCase` (`A` int); insert into MIXEDCASE values (1);");
        assertEquals(List.of("mixedcase"), List.copyOf(schema.tables.keySet()));
        assertEquals("MixedCase", schema.tables.get("mixedcase").name);
        assertEquals(1, schema.tables.get("mixedcase").statements.size());
    }
}
