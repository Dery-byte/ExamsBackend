package com.exam.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * On MySQL, lets the driver send a batch of inserts as one multi-row INSERT instead of one round trip per
 * row. Only JDBC batches are affected, and the only code that batches is a quiz submission's answer rows
 * (QuizEvaluationServiceImpl sets a batch size on its own session). Set here rather than in the properties
 * because only the MySQL driver knows the option; the H2 test database would reject it.
 * Applied before the pool opens its first connection.
 */
@Component
public class MySqlBatchInserts implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource ds && ds.getJdbcUrl() != null && ds.getJdbcUrl().startsWith("jdbc:mysql:")) {
            ds.addDataSourceProperty("rewriteBatchedStatements", "true");
        }
        return bean;
    }
}
