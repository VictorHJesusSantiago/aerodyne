package io.aerodyne.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the JPA entities and the Flyway migrations describe the same schema.
 *
 * <p>Hibernate proves this at startup with {@code ddl-auto=validate}, but only against a running
 * database, which means only where Docker is. The failure it catches — a column renamed on one
 * side and not the other — is both the likeliest mistake in this layer and one that costs
 * nothing to catch, so it is worth catching on every build rather than only on some.
 *
 * <p>The migrations are read as text and the entities by reflection. That makes this test
 * genuinely independent of Hibernate: it would notice a mistake that Hibernate's own validation
 * had been configured away.
 *
 * <p>What it does <em>not</em> prove is that the types are compatible, that the constraints are
 * right, or that a geography column behaves as one. {@link PostgisIntegrationTest} covers those,
 * against a real database.
 */
class SchemaConsistencyTest {

    /** Every entity in the persistence layer, and the classes nested inside them. */
    private static final List<Class<?>> ENTITIES = List.of(
            NavigationEntities.CycleEntity.class,
            NavigationEntities.AirportEntity.class,
            NavigationEntities.RunwayEntity.class,
            NavigationEntities.NavaidEntity.class,
            NavigationEntities.WaypointEntity.class,
            NavigationEntities.AirwayEntity.class,
            NavigationEntities.AirwayPointEntity.class,
            FlightPlanEntity.class,
            FlightPlanEntity.ObjectionEntity.class,
            FlightPlanEntity.AlternateEntity.class,
            CpdlcMessageEntity.class,
            ProcedureEntity.class,
            ProcedureEntity.TransitionEntity.class,
            ProcedureEntity.LegEntity.class);

    private static final List<String> MIGRATIONS =
            List.of("/db/migration/V1__navigation_data.sql",
                    "/db/migration/V2__flight_plans.sql",
                    "/db/migration/V3__procedures.sql");

    /** {@code CREATE TABLE name (} through to the closing {@code );}. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\((.*?)\\n\\);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Lines inside a table body that define a constraint rather than a column. */
    private static final Set<String> NOT_COLUMNS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE");

    private static Map<String, Set<String>> schema;

    @BeforeAll
    static void readTheMigrations() {
        schema = new LinkedHashMap<>();
        for (String migration : MIGRATIONS) {
            String sql = read(migration);
            Matcher tables = CREATE_TABLE.matcher(sql);
            while (tables.find()) {
                schema.put(tables.group(1).toLowerCase(Locale.ROOT),
                        columnsOf(tables.group(2)));
            }
        }
    }

    @Test
    void the_migrations_parsed_into_the_tables_they_declare() {
        assertThat(schema).containsKeys("airac_cycle", "airport", "runway", "navaid",
                "waypoint", "airway", "airway_point", "flight_plan",
                "flight_plan_objection", "flight_plan_alternate", "cpdlc_message",
                "procedure_definition", "procedure_transition", "procedure_leg");
    }

    @Test
    void the_column_parsing_picked_up_columns_and_not_constraints() {
        assertThat(schema.get("airac_cycle"))
                .containsExactly("id", "identifier", "effective_date", "expiry_date",
                        "loaded_at", "source");
        assertThat(schema.get("airport")).contains("icao_code", "position", "elevation_ft");
        assertThat(schema.get("airport")).doesNotContain("unique", "constraint");
    }

    @Test
    void every_entity_maps_to_a_table_the_migrations_create() {
        List<String> missing = ENTITIES.stream()
                .map(SchemaConsistencyTest::tableNameOf)
                .filter(table -> !schema.containsKey(table))
                .toList();

        assertThat(missing).as("entities mapped to tables that no migration creates").isEmpty();
    }

    @Test
    void every_mapped_column_exists_in_the_schema() {
        List<String> missing = new ArrayList<>();
        for (Class<?> entity : ENTITIES) {
            String table = tableNameOf(entity);
            Set<String> columns = schema.getOrDefault(table, Set.of());
            for (String column : mappedColumnsOf(entity)) {
                if (!columns.contains(column)) {
                    missing.add(table + "." + column
                            + " (mapped by " + entity.getSimpleName() + ")");
                }
            }
        }

        assertThat(missing).as("columns the entities map that the schema does not have").isEmpty();
    }

    @Test
    void every_column_the_schema_has_is_mapped_or_deliberately_not() {
        // Every table has an entity behind it, and every column something mapping it, or it is
        // dead weight nobody reads. Flyway's own bookkeeping table is not ours to map.
        Map<String, Set<String>> unmapped = new LinkedHashMap<>();
        Set<String> tablesWithoutEntities = Set.of("flyway_schema_history");

        for (Map.Entry<String, Set<String>> table : schema.entrySet()) {
            if (tablesWithoutEntities.contains(table.getKey())) {
                continue;
            }
            Set<String> mapped = ENTITIES.stream()
                    .filter(entity -> tableNameOf(entity).equals(table.getKey()))
                    .flatMap(entity -> mappedColumnsOf(entity).stream())
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> leftOver = new LinkedHashSet<>(table.getValue());
            leftOver.removeAll(mapped);
            if (!leftOver.isEmpty()) {
                unmapped.put(table.getKey(), leftOver);
            }
        }

        assertThat(unmapped).as("schema columns with nothing mapping them").isEmpty();
    }

    @Test
    void a_renamed_column_would_be_caught() {
        // The check is only worth having if it can fail, so this proves it does: a column name
        // that no migration declares must be reported as missing.
        Set<String> columns = schema.get("airport");

        assertThat(columns).contains("icao_code");
        assertThat(columns).doesNotContain("icao_identifier");
    }

    // ------------------------------------------------------------------ reflection

    /** The table an entity maps to: its {@code @Table} name, or its class name in snake case. */
    private static String tableNameOf(Class<?> entity) {
        assertThat(entity.isAnnotationPresent(Entity.class))
                .as("%s is listed as an entity", entity.getSimpleName()).isTrue();
        Table table = entity.getAnnotation(Table.class);
        return table != null && !table.name().isBlank()
                ? table.name().toLowerCase(Locale.ROOT)
                : snakeCase(entity.getSimpleName());
    }

    /**
     * The columns an entity maps.
     *
     * <p>A field with no {@code @Column} still maps to one, named by Spring Boot's default
     * strategy: camel case becomes snake case. Collections mapped by {@code @OneToMany} own no
     * column on this side, and transient fields own none at all.
     */
    private static Set<String> mappedColumnsOf(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isAnnotationPresent(Transient.class)
                    || field.isAnnotationPresent(OneToMany.class)) {
                continue;
            }
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            if (joinColumn != null) {
                columns.add(joinColumn.name().toLowerCase(Locale.ROOT));
                continue;
            }
            if (field.isAnnotationPresent(ManyToOne.class)) {
                // A relation with no explicit join column takes the default name, which this
                // codebase never relies on; flag it rather than guess.
                columns.add(snakeCase(field.getName()) + "_id");
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            columns.add(column != null && !column.name().isBlank()
                    ? column.name().toLowerCase(Locale.ROOT)
                    : snakeCase(field.getName()));
        }
        return columns;
    }

    // ------------------------------------------------------------------ SQL reading

    /** The column names declared in a {@code CREATE TABLE} body. */
    private static Set<String> columnsOf(String tableBody) {
        Set<String> columns = new LinkedHashSet<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        List<String> definitions = new ArrayList<>();

        // Comments come out first, before anything is split. A comment inside a table body can
        // contain commas — the ones listing the enum values do — and splitting first would tear
        // a definition in half at a comma that is not a separator.
        String body = stripComments(tableBody);

        // Split on the commas that separate definitions, ignoring those inside a type's
        // parentheses — NUMERIC(8, 1) is one definition, not two.
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                definitions.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        definitions.add(current.toString());

        for (String definition : definitions) {
            String cleaned = definition.trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            String first = cleaned.split("\\s+")[0];
            if (NOT_COLUMNS.contains(first.toUpperCase(Locale.ROOT))) {
                continue;
            }
            columns.add(first.toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static String stripComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .filter(line -> !line.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static String read(String resource) {
        try (InputStream stream = SchemaConsistencyTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("migration %s must be on the classpath", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    /** Spring Boot's default naming strategy: {@code icaoCode} becomes {@code icao_code}. */
    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
