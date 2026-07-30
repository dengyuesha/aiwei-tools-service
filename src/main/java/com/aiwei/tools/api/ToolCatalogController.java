package com.aiwei.tools.api;

import com.aiwei.tools.catalog.ToolCatalog;
import com.aiwei.tools.catalog.ToolDefinition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 暴露稳定工具目录，供调用方启动检查和运维查看。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolCatalogController {

    private final ToolCatalog catalog;

    /**
     * 创建目录控制器。
     *
     * @param catalog 工具目录
     */
    public ToolCatalogController(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 返回全部逻辑工具及迁移状态。
     *
     * @return 工具目录
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, ToolDefinition> tools() {
        return catalog.all();
    }
}

