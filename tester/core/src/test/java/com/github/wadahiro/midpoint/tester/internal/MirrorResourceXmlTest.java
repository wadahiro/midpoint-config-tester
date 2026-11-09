package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.ResourceOptions;
import com.github.wadahiro.midpoint.tester.mirror.MirrorClass;

/** The resource-to-mirror transform on its own (no midPoint needed). */
class MirrorResourceXmlTest {

    static final String HEAD = "<resource oid='r1' xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'"
            + " xmlns:ri='http://midpoint.evolveum.com/xml/ns/public/resource/instance-3'"
            + " xmlns:icfs='http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/resource-schema-3'"
            + " xmlns:xsd='http://www.w3.org/2001/XMLSchema'"
            + " xmlns:ra='http://midpoint.evolveum.com/xml/ns/public/resource/annotation-3'"
            + " xmlns:a='http://prism.evolveum.com/xml/ns/public/annotation-3'><name>R</name>"
            + "<connectorRef oid='real-connector'/><connectorConfiguration><x/></connectorConfiguration>";

    @Test
    void storedSchema_activeDirectoryStyle() {
        MirrorResourceXml r = transform(HEAD
                + "<schema><definition><xsd:schema>"
                + "<xsd:complexType name='user'><xsd:annotation><xsd:appinfo>"
                + "<ra:identifier>ri:objectGUID</ra:identifier><ra:namingAttribute>ri:distinguishedName</ra:namingAttribute>"
                + "</xsd:appinfo></xsd:annotation><xsd:sequence>"
                + "<xsd:element name='distinguishedName' type='xsd:string'/>"
                + "<xsd:element name='objectGUID' type='xsd:string'><xsd:annotation><xsd:appinfo>"
                + "<a:access>read</a:access></xsd:appinfo></xsd:annotation></xsd:element>"
                + "<xsd:element name='proxyAddresses' type='xsd:string' maxOccurs='unbounded'/>"
                + "<xsd:element name='userAccountControl' type='xsd:int'/>"
                + "<xsd:element name='whenCreated' type='xsd:dateTime'><xsd:annotation><xsd:appinfo>"
                + "<a:access>read</a:access></xsd:appinfo></xsd:annotation></xsd:element>"
                + "</xsd:sequence></xsd:complexType>"
                + "<xsd:complexType name='group'/>"
                + "</xsd:schema></definition></schema>"
                + "<schemaHandling>"
                + "<objectType><kind>account</kind><objectClass>ri:user</objectClass>"
                + "<attribute><ref>ri:distinguishedName</ref></attribute></objectType>"
                + "<objectType><kind>entitlement</kind><objectClass>ri:group</objectClass></objectType>"
                + "</schemaHandling>"
                + "<capabilities><native><cap:activation xmlns:cap='urn:cap'/></native><configured/></capabilities>"
                + "<correlation><path>$shadow/attributes/ri:distinguishedName</path></correlation>"
                + "</resource>");

        MirrorClass user = classes(r).get("user");
        assertThat(user.connIdName).isEqualTo("user");
        assertThat(user.namingAttribute).isEqualTo("distinguishedName");
        assertThat(user.uidAttribute).isEqualTo("objectGUID");
        assertThat(user.attributes).containsOnlyKeys("proxyAddresses", "userAccountControl", "whenCreated");
        assertThat(user.attributes.get("proxyAddresses").multiValued).isTrue();
        assertThat(user.attributes.get("userAccountControl").type).isEqualTo(Integer.class);
        assertThat(user.attributes.get("whenCreated").creatable).isFalse();
        assertThat(user.activation).isTrue();
        assertThat(user.password).isFalse();
        assertThat(r.accountClasses).containsExactly("user");
        assertThat(r.otherClasses).containsExactly("group");
        // only the connector changes; the stored schema and native capabilities are fetched again from the mirror
        assertThat(r.xml)
                .contains("$shadow/attributes/ri:distinguishedName", ">ri:distinguishedName</ref>", "<configured/>")
                .contains("connector-oid", "mirror:instanceId", ">r1<")
                .doesNotContain("real-connector", "<x/>", "<schema>", "<native>");
    }

    @Test
    void storedSchema_frameworkAttributeNames() {
        MirrorResourceXml r = transform(HEAD
                + "<schema><definition><xsd:schema><xsd:complexType name='person'><xsd:sequence>"
                + "<xsd:element name='login'><xsd:annotation><xsd:appinfo>"
                + "<ra:frameworkAttributeName>__NAME__</ra:frameworkAttributeName></xsd:appinfo></xsd:annotation></xsd:element>"
                + "<xsd:element name='employeeId'><xsd:annotation><xsd:appinfo>"
                + "<ra:frameworkAttributeName>__UID__</ra:frameworkAttributeName></xsd:appinfo></xsd:annotation></xsd:element>"
                + "<xsd:element name='mail'/>"
                + "</xsd:sequence></xsd:complexType></xsd:schema></definition></schema>"
                + "<schemaHandling><objectType><objectClass>ri:person</objectClass>"
                + "<attribute><ref>ri:login</ref></attribute></objectType></schemaHandling></resource>");

        MirrorClass person = classes(r).get("person");
        assertThat(person.namingAttribute).isEqualTo("login");
        assertThat(person.uidAttribute).isEqualTo("employeeId");
        assertThat(person.attributes).containsOnlyKeys("mail");
    }

    @Test
    void storedLegacySchema() {
        MirrorResourceXml r = transform(HEAD
                + "<schema><definition><xsd:schema>"
                + "<xsd:complexType name='AccountObjectClass'><xsd:sequence><xsd:element ref='icfs:name'/>"
                + "<xsd:element name='mail'/></xsd:sequence></xsd:complexType>"
                + "<xsd:complexType name='GroupObjectClass'/><xsd:complexType name='CustomprivilegeObjectClass'/>"
                + "</xsd:schema></definition></schema></resource>");

        Map<String, MirrorClass> classes = classes(r);
        assertThat(classes.get("AccountObjectClass").connIdName).isEqualTo("__ACCOUNT__");
        assertThat(classes.get("AccountObjectClass").namingAttribute).isNull();      // icfs:name
        assertThat(classes.get("AccountObjectClass").uidAttribute).isNull();         // icfs:uid, the name
        assertThat(classes.get("GroupObjectClass").connIdName).isEqualTo("__GROUP__");
        assertThat(classes.get("CustomprivilegeObjectClass").connIdName).isEqualTo("privilege");
    }

    @Test
    void noStoredSchema_csvStyle() {
        MirrorResourceXml r = transform(HEAD + "<schemaHandling><objectType>"
                + "<objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute>"
                + "<attribute><ref>ri:roles</ref><limitations><maxOccurs>unbounded</maxOccurs></limitations></attribute>"
                + "<attribute><ref>mail</ref></attribute>"
                + "<credentials><password><outbound/></password></credentials>"
                + "</objectType></schemaHandling></resource>");

        MirrorClass account = classes(r).get("AccountObjectClass");
        assertThat(account.connIdName).isEqualTo("__ACCOUNT__");
        assertThat(account.namingAttribute).isNull();
        assertThat(account.attributes).containsOnlyKeys("roles", "mail");
        assertThat(account.attributes.get("roles").multiValued).isTrue();
        assertThat(account.attributes.get("mail").multiValued).isFalse();
        assertThat(account.password).isTrue();        // from the credentials mapping
        assertThat(account.activation).isFalse();
    }

    @Test
    void validityDates() {
        // from the native capabilities (as midPoint stores what the real connector has)
        MirrorResourceXml stored = transform(HEAD + "<schemaHandling><objectType>"
                + "<objectClass>ri:AccountObjectClass</objectClass><attribute><ref>icfs:name</ref></attribute>"
                + "</objectType></schemaHandling>"
                + "<capabilities><native><cap:activation xmlns:cap='urn:cap'><cap:status/><cap:validFrom/>"
                + "</cap:activation></native></capabilities></resource>");
        MirrorClass account = classes(stored).get("AccountObjectClass");
        assertThat(account.activation).isTrue();
        assertThat(account.validFrom).isTrue();
        assertThat(account.validTo).isFalse();

        // without them: from the activation mappings of schemaHandling
        MirrorResourceXml mapped = transform(HEAD + "<schemaHandling><objectType>"
                + "<objectClass>ri:AccountObjectClass</objectClass><attribute><ref>icfs:name</ref></attribute>"
                + "<activation><validTo><outbound/></validTo></activation>"
                + "</objectType></schemaHandling></resource>");
        account = classes(mapped).get("AccountObjectClass");
        assertThat(account.activation).isTrue();
        assertThat(account.validFrom).isFalse();
        assertThat(account.validTo).isTrue();

        // an administrative status alone has no dates
        MirrorResourceXml status = transform(HEAD + "<schemaHandling><objectType>"
                + "<objectClass>ri:AccountObjectClass</objectClass><attribute><ref>icfs:name</ref></attribute>"
                + "<activation><administrativeStatus><outbound/></administrativeStatus></activation>"
                + "</objectType></schemaHandling></resource>");
        account = classes(status).get("AccountObjectClass");
        assertThat(account.activation).isTrue();
        assertThat(account.validFrom).isFalse();
        assertThat(account.validTo).isFalse();
    }

    @Test
    void passwordsAreReadAsTheRealConnectorReadsThem() {
        String body = "<schemaHandling><objectType><objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute><credentials><password><outbound/></password></credentials>"
                + "</objectType></schemaHandling>";
        String csv = "xmlns:csv='http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/"
                + "com.evolveum.polygon.connector-csv/com.evolveum.polygon.connector.csv.CsvConnector'";
        String ldap = "xmlns:ldap='http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/"
                + "com.evolveum.polygon.connector-ldap/com.evolveum.polygon.connector.ldap.LdapConnector'";

        // ConnId's default: not readable
        assertReading(HEAD + body + "</resource>", MirrorClass.PasswordReading.UNREADABLE, false);
        // the CSV connector's password column: readable, returned by default
        assertReading(withConfiguration("<icfc:configurationProperties " + csv + "><csv:uniqueAttribute>login"
                + "</csv:uniqueAttribute><csv:passwordAttribute>password</csv:passwordAttribute>"
                + "</icfc:configurationProperties>") + body + "</resource>", MirrorClass.PasswordReading.READABLE, true);
        // the LDAP connector's strategies: readable and incompleteRead only when asked for; unreadable by default
        assertReading(withConfiguration("<icfc:configurationProperties " + ldap + "><ldap:passwordReadStrategy>"
                + "readable</ldap:passwordReadStrategy></icfc:configurationProperties>") + body + "</resource>",
                MirrorClass.PasswordReading.READABLE, false);
        assertReading(withConfiguration("<icfc:configurationProperties " + ldap + "><ldap:passwordReadStrategy>"
                + "incompleteRead</ldap:passwordReadStrategy></icfc:configurationProperties>") + body + "</resource>",
                MirrorClass.PasswordReading.INCOMPLETE, false);
        assertReading(withConfiguration("<icfc:configurationProperties " + ldap + "><ldap:host>h</ldap:host>"
                + "</icfc:configurationProperties>") + body + "</resource>", MirrorClass.PasswordReading.UNREADABLE,
                false);
        // the native capabilities, if listed, decide (as midPoint stored them from the real connector)
        assertReading(HEAD + body + "<capabilities><native><cap:credentials xmlns:cap='urn:cap'><cap:password>"
                + "<cap:returnedByDefault>false</cap:returnedByDefault><cap:readable>true</cap:readable>"
                + "</cap:password></cap:credentials></native></capabilities></resource>",
                MirrorClass.PasswordReading.READABLE, false);
        assertReading(HEAD + body + "<capabilities><native><cap:credentials xmlns:cap='urn:cap'><cap:password/>"
                + "</cap:credentials></native></capabilities></resource>", MirrorClass.PasswordReading.UNREADABLE,
                false);
        // no password capability: nothing to read
        MirrorResourceXml none = transform(withConfiguration("<icfc:configurationProperties " + csv
                + "><csv:passwordAttribute>password</csv:passwordAttribute></icfc:configurationProperties>")
                + "<schemaHandling><objectType><objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute></objectType></schemaHandling></resource>");
        assertThat(classes(none).get("AccountObjectClass").passwordReading)
                .isEqualTo(MirrorClass.PasswordReading.UNREADABLE);
    }

    private static String withConfiguration(String properties) {
        return HEAD.replace("<connectorConfiguration><x/></connectorConfiguration>", "<connectorConfiguration"
                + " xmlns:icfc='http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3'>"
                + properties + "</connectorConfiguration>");
    }

    private static void assertReading(String xml, MirrorClass.PasswordReading reading, boolean returnedByDefault) {
        MirrorClass account = classes(transform(xml)).get("AccountObjectClass");
        assertThat(account.password).isTrue();
        assertThat(account.passwordReading).as(xml).isEqualTo(reading);
        assertThat(account.passwordReturnedByDefault).as(xml).isEqualTo(returnedByDefault);
    }

    @Test
    void noStoredSchema_associations() {
        MirrorResourceXml r = transform(HEAD + "<schemaHandling>"
                + "<objectType><kind>account</kind><objectClass>ri:user</objectClass>"
                + "<association><ref>ri:group</ref><kind>entitlement</kind><intent>group</intent>"
                + "<direction>subjectToObject</direction><associationAttribute>ri:memberOf</associationAttribute>"
                + "<valueAttribute>ri:dn</valueAttribute></association>"
                + "<association><ref>ri:list</ref><kind>entitlement</kind><intent>list</intent>"
                + "<direction>objectToSubject</direction><associationAttribute>ri:members</associationAttribute>"
                + "<valueAttribute>ri:mail</valueAttribute></association>"
                + "</objectType>"
                + "<objectType><kind>entitlement</kind><intent>group</intent><objectClass>ri:group</objectClass></objectType>"
                + "<objectType><kind>entitlement</kind><intent>list</intent><objectClass>ri:list</objectClass></objectType>"
                + "</schemaHandling></resource>", ResourceOptions.defaults().namingAttribute("ri:dn"));

        Map<String, MirrorClass> classes = classes(r);
        assertThat(classes.get("user").namingAttribute).isEqualTo("dn");
        assertThat(classes.get("user").attributes).containsOnlyKeys("memberOf", "mail");
        assertThat(classes.get("user").attributes.get("memberOf").multiValued).isTrue();
        assertThat(classes.get("group").attributes).isEmpty();                  // dn is the name
        assertThat(classes.get("list").attributes).containsOnlyKeys("members");
        assertThat(classes.get("list").attributes.get("members").multiValued).isTrue();
        assertThat(r.otherClasses).containsExactly("group", "list");
    }

    @Test
    void optionsOverrideTheStoredSchema() {
        MirrorResourceXml r = transform(HEAD
                + "<schema><definition><xsd:schema><xsd:complexType name='person'><xsd:sequence>"
                + "<xsd:element name='login'/><xsd:element name='employeeId'/>"
                + "</xsd:sequence></xsd:complexType></xsd:schema></definition></schema></resource>",
                ResourceOptions.defaults().namingAttribute("login").identifier("employeeId"));

        MirrorClass person = classes(r).get("person");
        assertThat(person.namingAttribute).isEqualTo("login");
        assertThat(person.uidAttribute).isEqualTo("employeeId");
        assertThat(person.attributes).isEmpty();
    }

    @Test
    void noGuessingWithoutStoredSchema() {
        // a hand-written definition naming accounts by ri:dn: nothing assumes that dn is the naming attribute
        assertThatThrownBy(() -> transform(HEAD + "<schemaHandling><objectType><objectClass>ri:inetOrgPerson</objectClass>"
                + "<attribute><ref>ri:dn</ref></attribute></objectType></schemaHandling></resource>"))
                .hasMessageContaining("cannot tell which attribute names accounts")
                .hasMessageContaining("ResourceOptions.defaults().namingAttribute");
    }

    static final String SCIM_LIKE = HEAD
            + "<schema><definition><xsd:schema>"
            + "<xsd:complexType name='User'><xsd:annotation><xsd:appinfo><ra:identifier>ri:id</ra:identifier>"
            + "<ra:namingAttribute>ri:userName</ra:namingAttribute></xsd:appinfo></xsd:annotation><xsd:sequence>"
            + "<xsd:element name='userName'/><xsd:element name='id'/>"
            + "<xsd:element name='groups' maxOccurs='unbounded'/></xsd:sequence></xsd:complexType>"
            + "<xsd:complexType name='Group'><xsd:annotation><xsd:appinfo><ra:identifier>ri:id</ra:identifier>"
            + "<ra:namingAttribute>ri:displayName</ra:namingAttribute></xsd:appinfo></xsd:annotation><xsd:sequence>"
            + "<xsd:element name='displayName'/><xsd:element name='id'/>"
            + "<xsd:element name='members' maxOccurs='unbounded'/></xsd:sequence></xsd:complexType>"
            + "</xsd:schema></definition></schema>"
            + "<schemaHandling>"
            + "<objectType><kind>account</kind><objectClass>ri:User</objectClass>"
            + "<association><ref>ri:group</ref><kind>entitlement</kind>"
            + "<associationAttribute>ri:members</associationAttribute><valueAttribute>ri:id</valueAttribute>"
            + "</association></objectType>"
            + "<objectType><kind>entitlement</kind><objectClass>ri:Group</objectClass></objectType>"
            + "</schemaHandling></resource>";

    @Test
    void computedAttributeAndIntegrityFollowTheAssociation() {
        MirrorResourceXml r = transform(SCIM_LIKE,
                ResourceOptions.defaults().computed("groups", "Group", "members").referentialIntegrity());

        MirrorClass user = classes(r).get("User");
        assertThat(user.computed).containsOnlyKeys("groups");
        assertThat(user.computed.get("groups").holderClass).isEqualTo("Group");
        assertThat(user.computed.get("groups").byUid).isTrue();              // members hold ids
        assertThat(user.attributes.get("groups").creatable).isFalse();
        assertThat(r.referentialIntegrity).isTrue();
        assertThat(r.references).hasSize(1);
        assertThat(r.references.get(0).holderAttribute).isEqualTo("members");
        assertThat(r.references.get(0).valueClass).isEqualTo("User");
    }

    @Test
    void noReferentialIntegrityOrCaseInsensitiveNamesUnlessAsked() {
        MirrorResourceXml r = transform(SCIM_LIKE);
        assertThat(r.references).hasSize(1);
        assertThat(r.referentialIntegrity).isFalse();
        assertThat(r.caseInsensitiveNames).isFalse();
        assertThat(transform(SCIM_LIKE, ResourceOptions.defaults().caseInsensitiveNames()).caseInsensitiveNames)
                .isTrue();
    }

    @Test
    void frameworkSettingsOfTheConfigurationStay() {
        String icfc = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3";
        MirrorResourceXml r = transform(HEAD.replace("<connectorConfiguration><x/></connectorConfiguration>",
                "<connectorConfiguration xmlns:icfc='" + icfc + "'>"
                        + "<icfc:configurationProperties><host>ldap.example.com</host></icfc:configurationProperties>"
                        + "<icfc:resultsHandlerConfiguration><icfc:enableFilteredResultsHandler>false"
                        + "</icfc:enableFilteredResultsHandler></icfc:resultsHandlerConfiguration>"
                        + "<icfc:timeouts><icfc:search>5000</icfc:search></icfc:timeouts>"
                        + "</connectorConfiguration>")
                + "<schemaHandling><objectType><kind>account</kind><objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute></objectType></schemaHandling></resource>");

        assertThat(r.xml).contains("mirror:instanceId", "enableFilteredResultsHandler>false<", "search>5000<")
                .doesNotContain("ldap.example.com");
    }

    @Test
    void computedAttributeNeedsAnAssociation() {
        assertThatThrownBy(() -> transform(SCIM_LIKE, ResourceOptions.defaults().computed("groups", "Group", "owners")))
                .hasMessageContaining("no association")
                .hasMessageContaining("Group.owners");
    }

    private static Map<String, MirrorClass> classes(MirrorResourceXml r) {
        return r.classes.stream().collect(Collectors.toMap(c -> c.midPointName, Function.identity()));
    }

    /** As midPoint exports objects: {@code <object xsi:type="c:ResourceType">}. */
    @Test
    void anObjectOfTheResourceType() {
        MirrorResourceXml r = transform(HEAD.replace("<resource oid='r1'", "<object oid='r1'"
                + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:type='ResourceType'")
                + "<schemaHandling><objectType><objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute><attribute><ref>ri:mail</ref></attribute>"
                + "</objectType></schemaHandling></object>");

        assertThat(classes(r).get("AccountObjectClass").attributes).containsOnlyKeys("mail");
    }

    /** A schema of generation constraints only is not a stored schema: the classes come from schemaHandling. */
    @Test
    void generationConstraintsAreNoStoredSchema() {
        MirrorResourceXml r = transform(HEAD
                + "<schema><generationConstraints><generateObjectClass>ri:AccountObjectClass</generateObjectClass>"
                + "</generationConstraints></schema>"
                + "<schemaHandling><objectType><objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute><attribute><ref>ri:mail</ref></attribute>"
                + "</objectType></schemaHandling></resource>");

        assertThat(classes(r).get("AccountObjectClass").attributes).containsOnlyKeys("mail");
    }

    /**
     * A resource deployed without its stored schema (midPoint fetches it from the real connector) takes the schema of an
     * export from a midPoint connected to it: its classes, multiplicities, naming attribute and identifier.
     */
    @Test
    void theStoredSchemaOfAnExport(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path export = tmp.resolve("resource-exported.xml");
        java.nio.file.Files.writeString(export, HEAD.replace("<resource ", "<object xsi:type='c:ResourceType' "
                + "xmlns:c='http://midpoint.evolveum.com/xml/ns/public/common/common-3' "
                + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' ")
                + "<schema><definition><xsd:schema>"
                + "<xsd:complexType name='inetOrgPerson'><xsd:annotation><xsd:appinfo>"
                + "<ra:identifier>ri:entryUUID</ra:identifier><ra:namingAttribute>ri:dn</ra:namingAttribute>"
                + "</xsd:appinfo></xsd:annotation><xsd:sequence>"
                + "<xsd:element name='dn' type='xsd:string'/><xsd:element name='entryUUID' type='xsd:string'/>"
                + "<xsd:element name='cn' type='xsd:string' maxOccurs='unbounded'/>"
                + "<xsd:element name='employeeNumber' type='xsd:string'/>"
                + "</xsd:sequence></xsd:complexType></xsd:schema></definition></schema></object>");
        String deployed = HEAD + "<schema><generationConstraints><generateObjectClass>ri:inetOrgPerson"
                + "</generateObjectClass></generationConstraints></schema><schemaHandling><objectType>"
                + "<kind>account</kind><objectClass>ri:inetOrgPerson</objectClass><attribute><ref>ri:cn</ref>"
                + "</attribute></objectType></schemaHandling></resource>";

        MirrorClass person = classes(transform(deployed, ResourceOptions.defaults().schema(export))).get("inetOrgPerson");
        assertThat(person.namingAttribute).isEqualTo("dn");
        assertThat(person.uidAttribute).isEqualTo("entryUUID");
        assertThat(person.attributes).containsOnlyKeys("cn", "employeeNumber");
        assertThat(person.attributes.get("cn").multiValued).isTrue();
        assertThat(person.attributes.get("employeeNumber").multiValued).isFalse();
        // the <schema> element copied out of the export as it is: the usual prefixes are declared for it
        String text = java.nio.file.Files.readString(export);
        java.nio.file.Path element = tmp.resolve("schema.xml");
        java.nio.file.Files.writeString(element, text.substring(text.indexOf("<schema>"), text.indexOf("</object>")));
        assertThat(classes(transform(deployed, ResourceOptions.defaults().schema(element))).get("inetOrgPerson")
                .attributes.get("cn").multiValued).isTrue();
        // without the export: schemaHandling only, which does not say what names the objects
        assertThatThrownBy(() -> transform(deployed)).hasMessageContaining("cannot tell which attribute names accounts");
    }

    /** An association's shortcut attribute (LDAP's memberOf) is on the members' class, computed from the holder's. */
    @Test
    void theShortcutAttributeOfAnAssociation() {
        MirrorResourceXml r = transform(HEAD + "<schemaHandling>"
                + "<objectType><kind>entitlement</kind><intent>group</intent><objectClass>ri:groupOfNames</objectClass>"
                + "<attribute><ref>ri:member</ref></attribute></objectType>"
                + "<objectType><kind>account</kind><objectClass>ri:inetOrgPerson</objectClass>"
                + "<association><ref>ri:group</ref><kind>entitlement</kind><intent>group</intent>"
                + "<direction>objectToSubject</direction><associationAttribute>ri:member</associationAttribute>"
                + "<valueAttribute>ri:dn</valueAttribute>"
                + "<shortcutAssociationAttribute>ri:memberOf</shortcutAssociationAttribute>"
                + "<shortcutValueAttribute>icfs:name</shortcutValueAttribute></association></objectType>"
                + "</schemaHandling></resource>", ResourceOptions.defaults().namingAttribute("dn"));

        assertThat(classes(r).get("inetOrgPerson").computed).containsKey("memberOf");
        assertThat(classes(r).get("groupOfNames").computed).doesNotContainKey("memberOf");
    }

    /** An attribute only a script reads: schemaHandling does not name it, the options add it. */
    @Test
    void attributesSchemaHandlingDoesNotName() {
        String body = HEAD + "<schemaHandling><objectType><objectClass>ri:AccountObjectClass</objectClass>"
                + "<attribute><ref>icfs:name</ref></attribute><attribute><ref>ri:mail</ref></attribute>"
                + "</objectType></schemaHandling></resource>";

        MirrorResourceXml r = transform(body, ResourceOptions.defaults().attribute("AccountObjectClass", "category"));
        assertThat(classes(r).get("AccountObjectClass").attributes).containsOnlyKeys("mail", "category");
        assertThatThrownBy(() -> transform(body, ResourceOptions.defaults().attribute("person", "category")))
                .hasMessageContaining("no object class person");
    }

    /** Classes named by different attributes: each its own, over the resource's. */
    @Test
    void namingAttributesByObjectClass() {
        MirrorResourceXml r = transform(HEAD + "<schemaHandling>"
                + "<objectType><kind>account</kind><objectClass>ri:User</objectClass>"
                + "<attribute><ref>ri:userName</ref></attribute><attribute><ref>ri:mail</ref></attribute></objectType>"
                + "<objectType><kind>entitlement</kind><objectClass>ri:Group</objectClass>"
                + "<attribute><ref>ri:displayName</ref></attribute><attribute><ref>ri:userName</ref></attribute>"
                + "</objectType></schemaHandling></resource>",
                ResourceOptions.defaults().namingAttribute("userName").namingAttribute("Group", "displayName"));

        assertThat(classes(r).get("User").namingAttribute).isEqualTo("userName");
        assertThat(classes(r).get("User").attributes).containsOnlyKeys("mail");
        assertThat(classes(r).get("Group").namingAttribute).isEqualTo("displayName");
        assertThat(classes(r).get("Group").attributes).containsOnlyKeys("userName");
    }

    private static MirrorResourceXml transform(String xml) {
        return transform(xml, ResourceOptions.defaults());
    }

    private static MirrorResourceXml transform(String xml, ResourceOptions options) {
        return MirrorResourceXml.transform(xml, "connector-oid", options);
    }
}
