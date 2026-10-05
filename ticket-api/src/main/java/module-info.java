/**
 * REST layer. Reads ticket data only through the exported {@code com.ticketing.core} facade;
 * the persistence classes in {@code com.ticketing.core.internal} are not visible to this module,
 * so a controller cannot bypass TicketService's tenant and role enforcement.
 */
module com.ticketing.api {
    requires com.ticketing.core;
    requires com.ticketing.security;

    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.web;
    requires spring.webmvc;
    requires spring.security.core;
    requires spring.security.config;
    requires spring.security.web;
    requires spring.security.oauth2.core;
    requires spring.security.oauth2.jose;
    requires spring.security.oauth2.resource.server;
    requires org.apache.tomcat.embed.core;   // provides jakarta.servlet in Spring Boot (no separate servlet-api jar)
    requires jakarta.validation;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;

    opens com.ticketing to spring.core, spring.beans, spring.context;
    opens com.ticketing.api to spring.core, spring.beans, spring.context, spring.web, spring.webmvc, com.fasterxml.jackson.databind;
}
