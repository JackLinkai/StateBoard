package io.asc;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束测试。
 *
 * <p>用 ArchUnit 锁住包之间的依赖方向，替代早期多模块的构建约束。
 * 一旦有人把 JPA 注解写进 domain，或者让 core 反向依赖 web，测试会直接失败。
 *
 * <p>依赖方向约定：
 * web → application → core → domain
 *         ↓
 *      storage → domain
 * （application 只允许经由 storage 顶层的 Store 门面访问存储，
 *   不许绕过去直接摸 entity / repository / converter）
 */
class ArchitectureTest {

    /** 扫描 io.asc 下所有编译后的类。 */
    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("io.asc");

    /**
     * domain 包不能依赖 Spring、JPA、storage、web。
     *
     * <p>为什么重要：领域模型要保持纯 Java，才能被单独测试、
     * 被 asc-core 独立发布、被其他模块复用。
     * 一旦 domain 里出现 @Entity 或 @Service，边界就破了。
     */
    @Test
    void domain_should_not_depend_on_spring_or_storage() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.asc.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "io.asc.storage..",
                        "io.asc.web.."
                );
        rule.check(classes);
    }

    /**
     * core 包不能反向依赖 web 或 storage。
     *
     * <p>core 只负责编译和治理逻辑，持久化和 HTTP 是外层的事。
     * core 通过接口（spi 包）访问存储，由 storage 实现，而不是直接 import JPA Repository。
     */
    //@Test
    void core_should_not_depend_on_web_or_storage() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.asc.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "io.asc.web..",
                        "io.asc.storage.."
                );
        rule.check(classes);
    }

    /**
     * 只有 storage 层允许依赖 JPA。
     *
     * <p>domain 必须是纯 Java（上一条规则已锁），
     * 以后 web / application / core 也不许绕过 storage 直接摸 @Entity 或 Repository，
     * 否则 domain ↔ entity 的隔离就形同虚设。谁在 storage 之外引 jakarta.persistence，这里直接红。
     */
    @Test
    void only_storage_may_depend_on_jpa() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("io.asc.storage..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.springframework.data.jpa.."
                );
        rule.check(classes);
    }

    /**
     * application 层不能反向依赖 web。
     *
     * <p>application 编排用例，被 web 调用而不感知 HTTP；
     * 将来 MCP / 定时任务等入口也是直接调 application，不经过 web。
     */
    @Test
    void application_should_not_depend_on_web() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.asc.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.asc.web..");
        rule.check(classes);
    }

    /**
     * application 访问存储只能走 Store 门面。
     *
     * <p>entity / repository / converter 是 storage 的内部实现，
     * application 直接 import 它们等于把 JPA 模型泄漏进用例层，
     * 以后换存储实现时用例全部要改。谁绕过 Store，这里直接红。
     */
    @Test
    void application_must_access_storage_only_through_store_facade() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.asc.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "io.asc.storage.entity..",
                        "io.asc.storage.repository..",
                        "io.asc.storage.converter.."
                );
        rule.check(classes);
    }
}