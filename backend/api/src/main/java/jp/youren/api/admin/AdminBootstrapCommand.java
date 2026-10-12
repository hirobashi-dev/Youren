package jp.youren.api.admin;

import java.io.Console;
import java.time.Clock;
import java.util.Arrays;
import jp.youren.database.DatabaseConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

// 交互命令仅在本机终端读取凭据，MFA种子只通过控制台展示，不写入参数或日志。
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(DatabaseConfiguration.class)
public class AdminBootstrapCommand {
  public static void main(String[] args) {
    if (args.length != 0) throw new IllegalArgumentException("此命令不接受凭据或其他命令行参数");
    Console console = System.console();
    if (console == null) throw new IllegalStateException("必须在可交互的本机终端运行管理员初始化命令");
    String login = console.readLine("管理员登录名: ");
    char[] password = console.readPassword("管理员口令（至少16位）: ");
    String secret = Totp.generateSecret();
    String uri =
        "otpauth://totp/Youren:"
            + java.net.URLEncoder.encode(login, java.nio.charset.StandardCharsets.UTF_8)
            + "?secret="
            + secret
            + "&issuer=Youren&algorithm=SHA1&digits=6&period=30";
    console.printf("请在认证器中添加此TOTP URI（仅显示一次）：%s%n", uri);
    char[] code = console.readPassword("输入认证器当前的六位验证码: ");
    try (var context =
        new SpringApplicationBuilder(AdminBootstrapCommand.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=never")
            .run()) {
      var service = context.getBean(AdminBootstrapService.class);
      service.createFirstReviewer(login, password, secret, new String(code));
      console.writer().println("首个管理员已创建；后续登录必须同时验证TOTP。");
    } catch (RuntimeException error) {
      console.writer().println("管理员初始化失败；请确认迁移、密钥配置和数据库状态。");
      System.exit(1);
    } finally {
      Arrays.fill(password, '\0');
      Arrays.fill(code, '\0');
    }
  }

  @Bean
  AdminBootstrapService adminBootstrapService(
      jp.youren.database.mapper.AdminMapper mapper,
      javax.sql.DataSource dataSource,
      org.springframework.transaction.PlatformTransactionManager manager,
      Environment environment) {
    return new AdminBootstrapService(
        mapper,
        dataSource,
        Clock.systemUTC(),
        manager,
        environment.getProperty("YOUREN_ADMIN_MFA_ENCRYPTION_KEY", ""));
  }
}
