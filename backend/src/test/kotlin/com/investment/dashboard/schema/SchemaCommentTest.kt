package com.investment.dashboard.schema

import com.investment.dashboard.TestcontainersConfiguration
import jakarta.persistence.Column
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Table
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.metamodel.mapping.ModelPart
import org.hibernate.metamodel.mapping.SelectableMapping
import org.hibernate.persister.entity.EntityPersister
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.lang.reflect.Field

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SchemaCommentTest(
    @Autowired private val entityManagerFactory: EntityManagerFactory,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    @DisplayName("엔티티 @Table·@Column comment 와 DB COMMENT 가 테이블·컬럼마다 글자 그대로 같다")
    fun entity_comments_match_database_comments() {
        val entityComments = entityComments()

        val databaseComments = databaseComments(entityComments.keys)

        assertThat(entityComments).`as`("비교한 엔티티 테이블·컬럼").isNotEmpty()
        assertThat(entityComments).allSatisfy { target, comment ->
            assertThat(comment).`as`("$target 의 엔티티 comment").isNotBlank()
        }
        assertThat(databaseComments).allSatisfy { target, comment ->
            assertThat(comment).`as`("$target 의 DB COMMENT").isNotBlank()
        }
        assertThat(databaseComments).containsExactlyInAnyOrderEntriesOf(entityComments)
    }

    private fun entityComments(): Map<String, String?> {
        val entities = mutableListOf<EntityPersister>()
        entityManagerFactory.unwrap(SessionFactoryImplementor::class.java).mappingMetamodel
            .forEachEntityDescriptor { entities += it }
        return entities
            .filterNot { it.mappedTableDetails.tableName.endsWith("_aud") }
            .flatMap { tableComment(it) + columnComments(it) }
            .toMap()
    }

    private fun tableComment(entity: EntityPersister): List<Pair<String, String?>> =
        listOf(entity.mappedTableDetails.tableName to entity.mappedClass.getAnnotation(Table::class.java)?.comment)

    private fun columnComments(entity: EntityPersister): List<Pair<String, String?>> {
        val type = entity.mappedClass
        val attributes = mutableListOf<Pair<String, ModelPart>>(
            entity.identifierMapping.attributeName to entity.identifierMapping,
        )
        entity.forEachAttributeMapping { attributes += it.attributeName to it }
        return attributes
            .map { (name, part) -> fieldOf(type, name) to part }
            .filter { (field, _) -> field.declaringClass == type }
            .flatMap { (field, part) ->
                val comment = field.getAnnotation(Column::class.java)?.comment
                selectables(part).map { "${it.containingTableExpression}.${it.selectionExpression}" to comment }
            }
    }

    private fun fieldOf(type: Class<*>, name: String): Field = generateSequence(type) { it.superclass }
        .firstNotNullOfOrNull { candidate -> candidate.declaredFields.firstOrNull { it.name == name } }
        ?: error("${type.simpleName} 에 $name 필드 없음")

    private fun selectables(part: ModelPart): List<SelectableMapping> {
        val selectables = mutableListOf<SelectableMapping>()
        part.forEachSelectable { _, selectable -> selectables += selectable }
        return selectables
    }

    private fun databaseComments(targets: Set<String>): Map<String, String?> = targets.associateWith { target ->
        val table = target.substringBefore('.')
        val column = target.substringAfter('.', "")
        if (column.isEmpty()) {
            jdbc.queryForObject(TABLE_COMMENT_SQL, String::class.java, table)
        } else {
            jdbc.queryForObject(COLUMN_COMMENT_SQL, String::class.java, table, column)
        }
    }

    private companion object {
        const val TABLE_COMMENT_SQL = "select obj_description(?::regclass, 'pg_class')"

        const val COLUMN_COMMENT_SQL = """
            select col_description(a.attrelid, a.attnum)
            from (select ?::regclass as relid) r
            left join pg_attribute a on a.attrelid = r.relid and a.attname = ? and not a.attisdropped
        """
    }
}
