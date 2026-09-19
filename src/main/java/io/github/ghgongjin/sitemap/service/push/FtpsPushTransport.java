package io.github.ghgongjin.sitemap.service.push;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;

/**
 * @ClassName FtpsPushTransport
 * @Description FTPS 传输实现（显式 TLS；证书链与主机名严格校验）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Component
public class FtpsPushTransport extends FtpPushTransport {

    public FtpsPushTransport() {
        super();
    }

    FtpsPushTransport(SSLContext sslContext) {
        super(sslContext);
    }

    @Override
    public PushProtocol protocol() {
        return PushProtocol.FTPS;
    }

    @Override
    boolean useTls() {
        return true;
    }
}
