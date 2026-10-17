package jp.youren.database;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// Spring事务和Mapper共用注入的数据源；不手动提交，不自动创建表。
@Configuration
@EnableTransactionManagement
@MapperScan("jp.youren.database.mapper")
public class DatabaseConfiguration {
  @Bean
  public SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(source);
    factory.setTypeHandlersPackage("jp.youren.database.type");
    factory.setMapperLocations(
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
    return factory.getObject();
  }
}
