package com.stayos.auth;

import com.stayos.staff.service.AccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Arrays;

@Component
@ConditionalOnProperty(name = "stayos.bootstrap-admin", havingValue = "true")
public class AdminBootstrap implements CommandLineRunner {
    private final AccountService accounts;
    public AdminBootstrap(AccountService accounts) { this.accounts = accounts; }
    @Override public void run(String... args) {
        var console = System.console();
        if (console == null) throw new IllegalStateException("请在真实终端运行初始化命令，不支持通过管道传入密码");
        String username = console.readLine("管理员账号: ");
        char[] password = console.readPassword("密码（至少12字符，不回显）: ");
        char[] confirmation = console.readPassword("再次输入密码: ");
        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) throw new IllegalArgumentException("两次密码不一致");
            accounts.bootstrap(username, new String(password));
            console.printf("管理员初始化成功。请退出初始化命令并启动服务。%n");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
