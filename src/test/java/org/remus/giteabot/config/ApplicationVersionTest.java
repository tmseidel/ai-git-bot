package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationVersionTest {

    @Test
    void get_withoutBuildProperties_fallsBackToDev() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        ApplicationVersion version = new ApplicationVersion(beanFactory.getBeanProvider(BuildProperties.class));

        assertEquals("dev", version.get());
    }

    @Test
    void get_withBuildProperties_returnsBuildVersion() {
        Properties entries = new Properties();
        entries.put("version", "1.29.0-SNAPSHOT");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("buildProperties", new BuildProperties(entries));

        ApplicationVersion version = new ApplicationVersion(beanFactory.getBeanProvider(BuildProperties.class));

        assertEquals("1.29.0-SNAPSHOT", version.get());
    }
}
