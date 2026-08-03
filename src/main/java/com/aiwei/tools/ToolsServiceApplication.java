package com.aiwei.tools;

import com.aiwei.tools.config.ToolsServiceProperties;
import com.aiwei.tools.flight.FlightProperties;
import com.aiwei.tools.map.MapProperties;
import com.aiwei.tools.music.HifiveProperties;
import com.aiwei.tools.music.LocalMusicProperties;
import com.aiwei.tools.rail.RailProperties;
import com.aiwei.tools.stock.StockProperties;
import com.aiwei.tools.state.StateProperties;
import com.aiwei.tools.web.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * aiwei 公共工具服务启动入口。
 */
@SpringBootApplication
@EnableConfigurationProperties({
        ToolsServiceProperties.class,
        RailProperties.class,
        FlightProperties.class,
        MapProperties.class,
        HifiveProperties.class,
        LocalMusicProperties.class,
        StockProperties.class,
        StateProperties.class,
        SearchProperties.class
})
public class ToolsServiceApplication {

    /**
     * 启动工具服务。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ToolsServiceApplication.class, args);
    }
}
