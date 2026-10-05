/**
 * Ticket domain and persistence.
 *
 * <p>JPMS is used as a security boundary: ONLY {@code com.ticketing.core} (the TicketService
 * facade and its request/response types) is exported. The JPA entity, the Postgres repository
 * and the Mongo document store live in {@code com.ticketing.core.internal}, which no other
 * module can compile against. The API layer therefore cannot bypass TicketService (and its
 * tenant / role checks) to touch data directly.
 */
module com.ticketing.core {
    requires transitive com.ticketing.security;

    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.tx;
    requires spring.jdbc;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.data.commons;
    requires spring.data.jpa;
    requires spring.data.mongodb;
    requires jakarta.persistence;
    requires jakarta.validation;
    requires org.hibernate.orm.core;
    requires org.slf4j;

    exports com.ticketing.core;

    // Frameworks need reflective access to internals; nothing else does.
    opens com.ticketing.core.internal to
            spring.core, spring.beans, spring.context, spring.data.commons,
            spring.data.jpa, spring.data.mongodb, org.hibernate.orm.core;
    opens com.ticketing.core to
            spring.core, spring.beans, spring.context;
}
