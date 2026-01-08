package com.ruoyi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动程序
 *
 * @author ruoyi
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class RuoYiApplication {
    public static void main(String[] args) {
        // System.setProperty("spring.devtools.restart.enabled", "false");
        SpringApplication.run(RuoYiApplication.class, args);
        System.out.println(
                "   _____  ______   _____             ____                 \n" +
                        "  / ____||  ____| |  __ \\           / __ \\                \n" +
                        " | (___  | |__    | |  | | ___ __ _| |  | | _ __   ___    \n" +
                        "  \\___ \\ |  __|   | |  | |/ _ \\\\ \\ / / |  | || '_ \\ / __|   \n" +
                        "  ____) || |      | |__| |  __/ \\ V /| |__| || |_) |\\__ \\   \n" +
                        " |_____/ |_|      |_____/ \\___|  \\_/  \\____/ | .__/ |___/   \n" +
                        "                                             | |            \n" +
                        "    (♥◠‿◠)ﾉﾞ SFDO系统启动成功  (oﾟvﾟ)ノ         |_|            \n" +
                        " -------------------------------------------------------- \n" +
                        "   :: System Status ::      (Running)                       \n" +
                        "   :: Deploy Mode   ::      (Ready to Launch)               \n" +
                        "   :: Powered by    ::      (RuoYi & Rocky Linux)           \n"
        );
    }
}
