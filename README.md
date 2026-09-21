# duckdb-learn

A small Java/Maven project for learning how to load and inspect CSV data with DuckDB.

The sample app reads `src/main/resources/citibike.csv`, creates an in-memory DuckDB table,
and prints rows from the imported data.

## Requirements

- Java 25
- Maven 3.9+

## Run

```sh
mvn exec:java
```

## Test

```sh
mvn test
```

## Project Layout

```text
src/main/java/com/bscllc/learning/App.java
src/main/java/com/bscllc/learning/TableSchemaExample.java
src/main/resources/citibike.csv
src/test/java/com/bscllc/learning/AppTest.java
```
