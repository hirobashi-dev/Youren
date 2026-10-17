package jp.youren.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class ApiServerAddressConfigurationTest {
  // 验证本机默认仅回环监听，同时允许部署环境通过显式变量覆盖。
  @Test
  void defaultsToLoopbackAndHonorsExplicitAddressOverride() throws Exception {
    var yaml =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .getFirst();

    var defaults = new MutablePropertySources();
    defaults.addLast(yaml);
    assertEquals(
        "127.0.0.1",
        new PropertySourcesPropertyResolver(defaults)
            .resolveRequiredPlaceholders((String) yaml.getProperty("server.address")));

    var overridden = new MutablePropertySources();
    overridden.addFirst(new MapPropertySource("environment", Map.of("SERVER_ADDRESS", "10.0.0.8")));
    overridden.addLast(yaml);
    assertEquals(
        "10.0.0.8",
        new PropertySourcesPropertyResolver(overridden)
            .resolveRequiredPlaceholders((String) yaml.getProperty("server.address")));
  }
}
