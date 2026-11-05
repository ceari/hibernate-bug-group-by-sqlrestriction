# Hibernate ORM reproducer: `GROUP BY` on a to-one association with `@SQLRestriction` on the target (regression in 7.4.8)

Based on the official [ORM 7 test case template](https://github.com/hibernate/hibernate-test-case-templates/tree/main/orm/hibernate-orm-7)
(`ORMUnitTestCase`).

## Problem

```java
@Entity @SQLRestriction("deleted = false")
class Parent { @Id Long id; boolean deleted; }

@Entity
class Child { @Id Long id; String name; @ManyToOne(fetch = LAZY) Parent parent; }
```

```hql
select c from Child c group by c.id, c.name, c.parent
```

For the same HQL, the generated SQL differs between 7.4.7 and 7.4.8+:

| Version | SELECT list | `GROUP BY` |
|---|---|---|
| 7.4.7.Final | `c1_0.id, c1_0.name, c1_0.parent_id` | `c1_0.id, c1_0.name, c1_0.parent_id` |
| 7.4.8.Final+ | `c1_0.id, c1_0.name, c1_0.parent_id` | `c1_0.id, c1_0.name, p2_0.id` (via a new `join Parent p2_0 on p2_0.id=c1_0.parent_id and (p2_0.deleted = false)`) |

Within one statement the association is rendered as the FK column of the root table in the SELECT list, but as the PK of a
joined table in the `GROUP BY`. A database that does not infer the equality of the two rejects the statement. SQL Server:

```
Column 'Child.parent_id' is invalid in the select list because it is not contained in either an aggregate function or the GROUP BY clause.
```

H2 is lenient (it derives the equality from the inner join condition), so the default test asserts on the generated SQL.

First bad version found by bisecting (`7.4.5`-`7.4.7` good, `7.4.8`-`7.4.14` bad): **7.4.8.Final**.
Likely trigger: HHH-20806 ("Join elimination in 7.x skips @SQLRestriction when querying by to-one association id"), which is
included in 7.4.8. Related: HHH-19568, HHH-20936.

## Run

Default (H2), fails on the latest 7.4.x:

```
mvn test
```

Check other versions (override the property, nothing else changes):

```
mvn test -Dversion.org.hibernate.orm=7.4.7.Final    # passes
mvn test -Dversion.org.hibernate.orm=7.4.8.Final    # fails
```

Expected: `groupByToOneAssociationWithSqlRestrictionOnTarget` fails on 7.4.8+ and passes on 7.4.7 and earlier.
The control `groupByToOneAssociationWithoutSqlRestrictionOnTarget` (same shape, target without `@SQLRestriction`) passes everywhere.

## Run against a real SQL Server (shows the actual database error)

```
docker run -d --rm --name hhh-mssql -e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=Repro_Passw0rd!' -p 14330:1433 \
  mcr.microsoft.com/mssql/server:2022-latest

mvn test -Pmssql \
  -Dhibernate.dialect=org.hibernate.dialect.SQLServerDialect \
  -Dhibernate.connection.driver_class=com.microsoft.sqlserver.jdbc.SQLServerDriver \
  "-Dhibernate.connection.url=jdbc:sqlserver://localhost:14330;encrypt=false;trustServerCertificate=true" \
  -Dhibernate.connection.username=sa "-Dhibernate.connection.password=Repro_Passw0rd!"
```

On 7.4.8+ the first test fails with the `SQLServerException` above. `groupByEntityWorkaround` (`group by c`) passes.
Messages like `Cannot find the object "Child"` during startup are harmless schema-cleanup noise.

## Workaround

`group by c` (the entity itself) instead of listing its attributes: Hibernate then expands the entity's own columns, so the
`GROUP BY` matches the SELECT list.
