package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationNameTest {

    @Test
    void get_withoutBuildProperties_fallsBackToTheProductName() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        ApplicationName name = new ApplicationName(beanFactory.getBeanProvider(BuildProperties.class));

        assertEquals("AI Git Bot", name.get());
    }

    @Test
    void get_withBuildProperties_returnsThePomName() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("buildProperties", new BuildProperties(entries("AI Git Bot Enterprise")));

        ApplicationName name = new ApplicationName(beanFactory.getBeanProvider(BuildProperties.class));

        assertEquals("AI Git Bot Enterprise", name.get());
    }

    @Test
    void get_withBlankBuildName_fallsBackToTheProductName() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("buildProperties", new BuildProperties(entries(" ")));

        ApplicationName name = new ApplicationName(beanFactory.getBeanProvider(BuildProperties.class));

        assertEquals("AI Git Bot", name.get());
    }

    private static Properties entries(String name) {
        Properties entries = new Properties();
        entries.put("name", name);
        return entries;
    }
}
