package com.querifylabs.tools.tpcds.parquet;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.DataFrameWriter;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class Runner {

    private static final String APP_NAME = "TpcdsConverter";
    private static final String SCHEMA_FILE_TEMPLATE = "%s.schema";

    public static void run(String dir, String tableName, String partitioning, Format format) throws Exception {
        var session = createSession();
        var schema = readSchema(tableName);
        var csvPath = getCsvPaths(dir, tableName);
        var outPath = format.path(dir, tableName);
        DataFrameWriter<Row> writer;
        if (partitioning != null) {
            writer = session
                .read()
                .schema(schema)
                .option("delimiter", "|")
                .csv(csvPath)
                .repartition(new Column(partitioning))
                .write()
                .option("compression", "snappy")
                .partitionBy(partitioning);
        } else {
            writer = session
                .read()
                .schema(schema)
                .option("delimiter", "|")
                .csv(csvPath)
                .repartition(1)
                .write()
                .option("compression", "snappy");
        }
        switch (format) {
            case ORC:
                writer.orc(outPath);
                break;
            case PARQUET:
                writer.parquet(outPath);
                break;
            default:
                throw new IllegalArgumentException("Unknown format: " + format);
        }
    }

    private static SparkSession createSession() {
        return SparkSession.builder()
            .master(String.format("local[%s]", Runtime.getRuntime().availableProcessors()))
            .appName(APP_NAME)
            .config("spark.sql.legacy.charVarcharAsString", "true")
            .getOrCreate();
    }

    private static StructType readSchema(String tableName) throws Exception {
        var schemaFile = String.format(SCHEMA_FILE_TEMPLATE, tableName);
        var stream = Main.class.getClassLoader().getResourceAsStream(schemaFile);
        try (stream) {
            if (stream == null) {
                throw new RuntimeException(String.format("Failed to get schema file %s.", schemaFile));
            }
            var ddlJoiner = new StringJoiner("\n");
            try (var reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    ddlJoiner.add(line);
                }
            }
            String ddl = ddlJoiner.toString();
            return StructType.fromDDL(ddl);
        }
    }

    private static String[] getCsvPaths(String dir, String tableName) throws Exception {
        var files = Files.list(Path.of(dir)).map(Path::toString).collect(Collectors.toSet());
        var res = files.stream()
                .filter((f) -> f.endsWith(tableName + ".dat"))
                .collect(Collectors.toList());
        if (res.isEmpty()) {
            res = files.stream()
                    .filter((f) -> f.contains(tableName + "_"))
                    .collect(Collectors.toList());
        }
        if (res.isEmpty()) {
            throw new RuntimeException("Table files not found: " + tableName);
        }
        return res.toArray(String[]::new);
    }
}
