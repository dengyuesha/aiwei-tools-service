package com.aiwei.tools.web;

import com.aiwei.tools.execution.ToolExecutionException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * 网页抓取的 SSRF 公网地址校验器。
 */
@Component
public class PublicUrlGuard {

    /**
     * 校验 URL 协议、端口、用户信息和 DNS 解析结果。
     *
     * @param rawUrl 用户提供的 URL
     * @return 规范化公网 URI
     * @throws ToolExecutionException URL 无效或指向非公网地址时抛出
     */
    public URI validate(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw blocked("只允许抓取 HTTP 或 HTTPS 网页。");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw blocked("网页地址格式不正确。");
            }
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) {
                throw blocked("网页抓取只允许标准 HTTP/HTTPS 端口。");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")) {
                throw blocked("不能访问本机或内网地址。");
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw blocked("网页域名无法解析。");
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw blocked("不能访问本机、内网或保留地址。");
                }
            }
            return uri;
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException("INVALID_URL",
                    "URL validation failed: " + error.getMessage(), false,
                    "网页地址无效或无法安全解析。");
        }
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first != 0 && first != 10 && first != 127
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 168)
                    && !(first >= 224);
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) != 0xfc
                    && !(first == 0xfe && ((bytes[1] & 0xc0) == 0x80));
        }
        return false;
    }

    private ToolExecutionException blocked(String summary) {
        return new ToolExecutionException("URL_BLOCKED", summary, false, summary);
    }
}
