package jp.youren.api;

import jp.youren.database.DatabaseConfiguration;
import jp.youren.shared.config.RuntimeDependenciesConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

// API仅启动工程健康接口；迁移需独立执行，失败不输出原始配置异常。
@SpringBootApplication
@Import({DatabaseConfiguration.class, RuntimeDependenciesConfiguration.class})
public class ApiApplication {
  public static void main(String[] args) {
    var application = new SpringApplication(ApiApplication.class);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    try {
      application.run(args);
      System.out.println("{\"event\":\"started\",\"service\":\"api\"}");
    } catch (Exception error) {
      System.err.println("{\"event\":\"startup_failed\",\"service\":\"api\"}");
      System.exit(1);
    }
  }
}
