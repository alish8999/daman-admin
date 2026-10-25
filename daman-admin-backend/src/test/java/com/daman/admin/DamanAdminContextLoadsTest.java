package com.daman.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole Spring context once. Unit tests construct beans by hand, so a startup-only
 * failure (a missing bean, e.g. Jackson 2's ObjectMapper injected where only Jackson 3's is
 * registered) compiles and passes everything else, then takes the live admin portal down.
 * Cloudflare settings are deliberately blank: the beans must construct without a token.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ctxloads;DB_CLOSE_DELAY=-1",
        "daman.cloudflare.account-id=",
        "daman.cloudflare.zone-id=",
        "daman.cloudflare.tunnel-id="
})
class DamanAdminContextLoadsTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsWithTheHostedStoreBeansAndNoCloudflareConfig() {
        for (String type : new String[]{
                "com.daman.admin.service.CloudflareClient",
                "com.daman.admin.service.HostedStoreProvisioningService",
                "com.daman.admin.service.HostedStoreProvisionLauncher",
                "com.daman.admin.service.ServerOps",
                "com.daman.admin.controller.HostedStoreController",
                "com.daman.admin.config.HostedInstanceVioraBackfillRunner"}) {
            try {
                assertThat(context.getBean(Class.forName(type))).as(type).isNotNull();
            } catch (ClassNotFoundException e) {
                throw new AssertionError(type, e);
            }
        }
    }
}
