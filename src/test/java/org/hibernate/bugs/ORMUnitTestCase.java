package org.hibernate.bugs;

import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import org.hibernate.annotations.SQLRestriction;
import org.hibernate.cfg.AvailableSettings;

import org.hibernate.testing.jdbc.SQLStatementInspector;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducer: {@code GROUP BY} on a to-one association whose target entity has an {@code @SQLRestriction}.
 * <p>
 * The query {@code select c from Child c group by c.id, c.name, c.parent} is valid HQL and works up to
 * 7.4.7.Final. Since 7.4.8.Final (HHH-20806 is the most likely trigger) Hibernate:
 * <ul>
 *     <li>renders the entity in the SELECT list using the foreign key column of the root table
 *     ({@code c1_0.parent_id}), but</li>
 *     <li>renders {@code c.parent} in the GROUP BY using the primary key of a joined target table
 *     ({@code p2_0.id}).</li>
 * </ul>
 * Both values are equal, but databases without functional dependency / join-equality inference reject the
 * statement, e.g. SQL Server:
 * <pre>
 * Column 'Child.parent_id' is invalid in the select list because it is not contained in either an
 * aggregate function or the GROUP BY clause.
 * </pre>
 * H2 is lenient and executes the statement, so this test asserts on the generated SQL instead.
 * See README.md for how to run the same test against SQL Server.
 */
@DomainModel(
		annotatedClasses = {
				ORMUnitTestCase.Parent.class,
				ORMUnitTestCase.Child.class,
				ORMUnitTestCase.Category.class,
				ORMUnitTestCase.Item.class
		}
)
@ServiceRegistry(
		settings = {
				@Setting(name = AvailableSettings.SHOW_SQL, value = "true"),
				@Setting(name = AvailableSettings.FORMAT_SQL, value = "true"),
		}
)
@SessionFactory(useCollectingStatementInspector = true)
class ORMUnitTestCase {

	@BeforeEach
	void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final Parent parent = new Parent( 1L, false );
			session.persist( parent );
			session.persist( new Child( 1L, "child", parent ) );

			final Category category = new Category( 1L );
			session.persist( category );
			session.persist( new Item( 1L, "item", category ) );
		} );
	}

	@AfterEach
	void tearDown(SessionFactoryScope scope) {
		scope.dropData();
	}

	/**
	 * FAILS on 7.4.8.Final and later, passes on 7.4.7.Final and earlier.
	 * <p>
	 * The SELECT list contains {@code c1_0.parent_id}, so the GROUP BY must group by the same column.
	 * On 7.4.8+ it groups by {@code p2_0.id} instead.
	 */
	@Test
	void groupByToOneAssociationWithSqlRestrictionOnTarget(SessionFactoryScope scope) {
		final String sql = execute(
				scope,
				"select c from Child c group by c.id, c.name, c.parent",
				Child.class
		);
		assertThat( groupByClause( sql ) )
				.as( "GROUP BY must use the same FK column that the SELECT list uses. SQL was:%n%s", sql )
				.contains( "parent_id" );
	}

	/**
	 * Control: identical shape, but the association target has no {@code @SQLRestriction}.
	 * Passes on all versions, the GROUP BY uses the FK column of the root table.
	 */
	@Test
	void groupByToOneAssociationWithoutSqlRestrictionOnTarget(SessionFactoryScope scope) {
		final String sql = execute(
				scope,
				"select i from Item i group by i.id, i.name, i.category",
				Item.class
		);
		assertThat( groupByClause( sql ) )
				.as( "SQL was:%n%s", sql )
				.contains( "category_id" );
	}

	/**
	 * Workaround: group by the entity itself, Hibernate then expands the entity's own columns
	 * (SQL Server) or its primary key (H2). Only executes the query, passes on all versions.
	 */
	@Test
	void groupByEntityWorkaround(SessionFactoryScope scope) {
		execute( scope, "select c from Child c group by c", Child.class );
	}

	private static <T> String execute(SessionFactoryScope scope, String hql, Class<T> resultClass) {
		final SQLStatementInspector inspector = scope.getCollectingStatementInspector();
		inspector.clear();
		scope.inTransaction( session -> {
			final List<T> result = session.createQuery( hql, resultClass ).getResultList();
			assertThat( result ).hasSize( 1 );
		} );
		final List<String> queries = inspector.getSqlQueries();
		return queries.get( queries.size() - 1 );
	}

	private static String groupByClause(String sql) {
		final int index = sql.toLowerCase().indexOf( "group by" );
		assertThat( index ).as( "no GROUP BY in:%n%s", sql ).isGreaterThanOrEqualTo( 0 );
		return sql.substring( index );
	}

	@Entity(name = "Parent")
	@SQLRestriction("deleted = false")
	static class Parent {
		@Id
		Long id;

		boolean deleted;

		Parent() {
		}

		Parent(Long id, boolean deleted) {
			this.id = id;
			this.deleted = deleted;
		}
	}

	@Entity(name = "Child")
	static class Child {
		@Id
		Long id;

		String name;

		@ManyToOne(fetch = FetchType.LAZY)
		Parent parent;

		Child() {
		}

		Child(Long id, String name, Parent parent) {
			this.id = id;
			this.name = name;
			this.parent = parent;
		}
	}

	@Entity(name = "Category")
	static class Category {
		@Id
		Long id;

		Category() {
		}

		Category(Long id) {
			this.id = id;
		}
	}

	@Entity(name = "Item")
	static class Item {
		@Id
		Long id;

		String name;

		@ManyToOne(fetch = FetchType.LAZY)
		Category category;

		Item() {
		}

		Item(Long id, String name, Category category) {
			this.id = id;
			this.name = name;
			this.category = category;
		}
	}
}
