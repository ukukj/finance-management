package com.investment.dashboard.architecture

import com.investment.dashboard.common.ClockConfig
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.Formatters
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvent
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import java.time.Clock
import java.util.Calendar
import java.util.Date
import java.util.Optional

class ArchitectureTest {

    @Test
    @DisplayName("main 코드는 현재 시각을 주입한 Clock 으로만 얻는다")
    fun main_code_reads_time_only_from_injected_clock() {
        noClasses().that().doNotBelongToAnyOf(ClockConfig::class.java)
            .should().callMethodWhere(javaTimeNowWithoutClock)
            .orShould().callMethodWhere(systemClockFactory)
            .orShould().callMethodWhere(kotlinClockSystemNow)
            .orShould().callMethodWhere(calendarGetInstance)
            .orShould().callMethod(System::class.java, "currentTimeMillis")
            .orShould().callConstructor(Date::class.java)
            .orShould().dependOnClassesThat(systemClockTimestampAnnotation)
            .check(mainClasses)
    }

    @Test
    @DisplayName("main 코드는 Optional 을 쓰지 않는다")
    fun main_code_does_not_use_optional() {
        noClasses().should().dependOnClassesThat().belongToAnyOf(Optional::class.java)
            .check(mainClasses)
    }

    @Test
    @DisplayName("엔티티는 data class 가 아니다")
    fun entities_are_not_data_classes() {
        classes().that().areAnnotatedWith(Entity::class.java)
            .or().areAnnotatedWith(Embeddable::class.java)
            .should(notBeDataClasses)
            .allowEmptyShould(true)
            .check(mainClasses)
    }

    @Test
    @DisplayName("엔티티 필드는 var 다")
    fun entity_fields_are_var() {
        fields().that().areDeclaredInClassesThat().areAnnotatedWith(Entity::class.java)
            .or().areDeclaredInClassesThat().areAnnotatedWith(Embeddable::class.java)
            .and().areNotStatic()
            .should().notBeFinal()
            .allowEmptyShould(true)
            .check(mainClasses)
    }

    @Test
    @DisplayName("main 코드 필드에 Double·Float 이 없다")
    fun main_fields_are_not_double_or_float() {
        fields().that().areDeclaredInClassesThat().resideInAPackage("com.investment.dashboard..")
            .should(notHaveTypeInvolvingDoubleOrFloat)
            .allowEmptyShould(true)
            .check(mainClasses)
    }

    @Test
    @DisplayName("테스트 함수는 DisplayName 이 있고 이름이 snake_case 다")
    fun tests_have_display_name_and_snake_case_name() {
        methods().that().areAnnotatedWith(Test::class.java)
            .should().beAnnotatedWith(DisplayName::class.java)
            .andShould().haveNameMatching("[a-z][a-z0-9_]*")
            .check(testClasses)
    }

    @Test
    @DisplayName("토스 코드는 RestClient 의 put·patch·delete·method 를 부르지 않는다")
    fun toss_does_not_call_write_methods() {
        noClasses().that().resideInAPackage("..toss..")
            .should().callMethod(RestClient::class.java, "put")
            .orShould().callMethod(RestClient::class.java, "patch")
            .orShould().callMethod(RestClient::class.java, "delete")
            .orShould().callMethod(RestClient::class.java, "method", HttpMethod::class.java)
            .allowEmptyShould(true)
            .check(mainClasses)
    }

    @Test
    @DisplayName("토스 코드의 POST 는 issueToken 에서만 나간다")
    fun toss_posts_only_from_issue_token() {
        classes().that().resideInAPackage("..toss..")
            .should(callRestClientPostOnlyFromIssueToken)
            .allowEmptyShould(true)
            .check(mainClasses)
    }

    @Test
    @DisplayName("토스 코드는 RestClient 외 HTTP 클라이언트를 쓰지 않는다")
    fun toss_uses_only_rest_client() {
        noClasses().that().resideInAPackage("..toss..")
            .should().dependOnClassesThat().areAssignableTo("org.springframework.web.client.RestTemplate")
            .orShould().dependOnClassesThat()
            .areAssignableTo("org.springframework.web.reactive.function.client.WebClient")
            .orShould().dependOnClassesThat().areAssignableTo("java.net.http.HttpClient")
            .orShould().dependOnClassesThat().areAssignableTo("java.net.URLConnection")
            .orShould().dependOnClassesThat().areAssignableTo("org.springframework.web.client.RestOperations")
            .orShould().dependOnClassesThat().resideInAPackage("org.springframework.web.service..")
            .allowEmptyShould(true)
            .check(mainClasses)
    }

    private companion object {
        val mainClasses: JavaClasses = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.investment.dashboard")

        val testClasses: JavaClasses = ClassFileImporter()
            .withImportOption(ImportOption.OnlyIncludeTests())
            .importPackages("com.investment.dashboard")

        val javaTimeNowWithoutClock: DescribedPredicate<JavaMethodCall> = DescribedPredicate.describe(
            "target is now(..) of a java.time type without a Clock parameter",
        ) {
            JavaClass.Predicates.resideInAPackage("java.time..").test(it.target.owner) &&
                it.target.name == "now" &&
                it.target.rawParameterTypes.none { type -> type.isEquivalentTo(Clock::class.java) }
        }

        val systemClockFactoryNames =
            setOf("system", "systemUTC", "systemDefaultZone", "tickSeconds", "tickMillis", "tickMinutes")

        val systemClockFactory: DescribedPredicate<JavaMethodCall> = DescribedPredicate.describe(
            "target is java.time.Clock.$systemClockFactoryNames",
        ) {
            it.target.owner.isEquivalentTo(Clock::class.java) && it.target.name in systemClockFactoryNames
        }

        val kotlinClockSystemNow: DescribedPredicate<JavaMethodCall> = DescribedPredicate.describe(
            "target is kotlin.time.Clock.System.now()",
        ) {
            it.target.owner.name == "kotlin.time.Clock\$System" && it.target.name == "now"
        }

        val calendarGetInstance: DescribedPredicate<JavaMethodCall> = DescribedPredicate.describe(
            "target is java.util.Calendar.getInstance(..)",
        ) {
            it.target.owner.isAssignableTo(Calendar::class.java) && it.target.name == "getInstance"
        }

        val systemClockTimestampAnnotationNames = setOf(
            "org.hibernate.annotations.CreationTimestamp",
            "org.hibernate.annotations.UpdateTimestamp",
            "org.hibernate.annotations.CurrentTimestamp",
            "org.springframework.data.annotation.CreatedDate",
            "org.springframework.data.annotation.LastModifiedDate",
        )

        val systemClockTimestampAnnotation: DescribedPredicate<JavaClass> = DescribedPredicate.describe(
            "are any of $systemClockTimestampAnnotationNames",
        ) {
            it.name in systemClockTimestampAnnotationNames
        }

        val floatingPointTypeNames = setOf("double", "java.lang.Double", "float", "java.lang.Float")

        val notHaveTypeInvolvingDoubleOrFloat =
            object : ArchCondition<JavaField>("not have a type involving double, Double, float or Float") {
                override fun check(item: JavaField, events: ConditionEvents) {
                    val floating = item.type.allInvolvedRawTypes.map { it.name }.filter { it in floatingPointTypeNames }
                    val typeName = Formatters.ensureCanonicalArrayTypeName(item.type.name)
                    val message = ConditionEvent.createMessage(item, "has type <$typeName> involving $floating")
                    events.add(SimpleConditionEvent(item, floating.isEmpty(), message))
                }
            }

        val notBeDataClasses = object : ArchCondition<JavaClass>("not be data classes (declare copy and component1)") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val hasCopy = item.methods.any { it.name == "copy" }
                val hasComponent1 = item.methods.any { it.name == "component1" }
                val message = ConditionEvent.createMessage(item, "declares copy: $hasCopy, component1: $hasComponent1")
                events.add(SimpleConditionEvent(item, !(hasCopy && hasComponent1), message))
            }
        }

        val callRestClientPostOnlyFromIssueToken =
            object : ArchCondition<JavaClass>("call RestClient.post() only from issueToken or its lambdas") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    item.methodCallsFromSelf
                        .filter { it.target.owner.isEquivalentTo(RestClient::class.java) && it.target.name == "post" }
                        .forEach { call ->
                            val caller = call.origin.name
                            val fromIssueToken = caller == "issueToken" || caller.startsWith("issueToken$")
                            events.add(SimpleConditionEvent(call, fromIssueToken, call.description))
                        }
                }
            }
    }
}
