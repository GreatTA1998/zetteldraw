package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared SQL migrations are the contract: Room's exported schema must
 * carry the same synced tables and columns (and nullability) as Postgres,
 * and Room's version must equal the number of server migrations.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class SchemaContractTest {
    private static final List<String> SYNCED = Arrays.asList("notebooks", "boards");

    @Test
    public void roomMatchesPostgresMigrations() throws Exception {
        File root = new File(System.getProperty("zd.repoRoot", ".."));
        File migrations = new File(root, "server/migrations");
        File[] files = migrations.listFiles((dir, name) -> name.matches("\\d{4}_.+\\.sql"));
        assertTrue("no migrations in " + migrations, files != null && files.length > 0);
        Arrays.sort(files);
        assertEquals("Room version == shared schema_version", files.length, ZettelDatabase.SCHEMA_VERSION);

        Map<String, Map<String, Boolean>> postgres = new TreeMap<>();
        for (File f : files) {
            parseCreateTables(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), postgres);
        }

        File roomJson = new File(root, "app/schemas/com.zetteldraw.penpoc.data.db.ZettelDatabase/"
                + ZettelDatabase.SCHEMA_VERSION + ".json");
        JSONObject db = new JSONObject(new String(Files.readAllBytes(roomJson.toPath()), StandardCharsets.UTF_8))
                .getJSONObject("database");
        assertEquals(ZettelDatabase.SCHEMA_VERSION, db.getInt("version"));
        Map<String, Map<String, Boolean>> room = new TreeMap<>();
        JSONArray entities = db.getJSONArray("entities");
        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.getJSONObject(i);
            Map<String, Boolean> cols = new LinkedHashMap<>();
            JSONArray fields = entity.getJSONArray("fields");
            for (int j = 0; j < fields.length(); j++) {
                JSONObject field = fields.getJSONObject(j);
                cols.put(field.getString("columnName"), field.getBoolean("notNull"));
            }
            room.put(entity.getString("tableName"), cols);
        }

        for (String table : SYNCED) {
            assertEquals("columns of " + table, new TreeMap<>(postgres.get(table)), new TreeMap<>(room.get(table)));
        }
    }

    /** Minimal parser for the "CREATE TABLE name ( col type ..., ... );" blocks in our migrations. */
    private static void parseCreateTables(String sql, Map<String, Map<String, Boolean>> out) {
        String noComments = sql.replaceAll("--[^\\n]*", "");
        Matcher m = Pattern.compile("CREATE TABLE (\\w+) \\((.*?)\\);", Pattern.DOTALL).matcher(noComments);
        while (m.find()) {
            Map<String, Boolean> cols = new LinkedHashMap<>();
            for (String line : m.group(2).split(",\\s*\\n")) {
                String def = line.trim();
                if (def.isEmpty() || def.toUpperCase().startsWith("PRIMARY KEY")) {
                    continue;
                }
                String name = def.split("\\s+")[0];
                String upper = def.toUpperCase();
                cols.put(name, upper.contains("NOT NULL") || upper.contains("PRIMARY KEY"));
            }
            out.put(m.group(1), cols);
        }
    }
}
