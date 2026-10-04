package com.exam.config;

import com.exam.model.exam.Department;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import jakarta.persistence.EntityManager;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A Hibernate lazy proxy must be writable as JSON by the app's ObjectMapper. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:jacksonproxy;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
class JacksonProxyJpaTest {

    @Autowired EntityManager em;

    private ObjectMapper appMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().ignoreHibernateProxyInternals().customize(builder);
        return builder.build();
    }

    private Object proxy() {
        Department d = new Department();
        d.setName("Computing");
        d.setCode("CMP");
        em.persist(d);
        em.flush();
        em.clear();
        Object ref = em.getReference(Department.class, d.getId());
        assertThat(ref).isInstanceOf(HibernateProxy.class);
        return ref;
    }

    @Test
    void lazyProxyIsWrittenAsTheEntity() throws Exception {
        Object ref = proxy();

        // The failure from the error log, with a mapper that lacks the fix
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(ref))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("ByteBuddyInterceptor");

        String json = appMapper().writeValueAsString(ref);
        assertThat(json).contains("\"name\":\"Computing\"").contains("\"code\":\"CMP\"")
                .doesNotContain("hibernateLazyInitializer");
    }

    @Test
    void mapsKeepEveryKey() throws Exception {
        String json = appMapper().writeValueAsString(Map.of("handler", 1, "marking", 2));
        assertThat(json).contains("\"handler\":1").contains("\"marking\":2");
    }
}
