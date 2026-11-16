# Typed names

Names in strings fail only when the test runs. With `names: { package: com.example.idm.names }` in the manifest, a
generator writes Java constants from the configuration at build time, and every verb takes them (the strings stay as the
escape hatch, for names the configuration does not have):

```java
// before
t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
t.task("Reconcile HR").run();
t.user("e1001").hasAssignment("Dept Sales").hasAccountOn("LDAP");
t.target("LDAP").account(dn).hasAttribute("ou", "Sales");

// after: a typo, or a role the configuration no longer has, does not compile
t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
        .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales"));
t.task(Tasks.RECONCILE_HR).run();
t.user("e1001").hasAssignment(Roles.DEPT_SALES).hasAccountOn(Resources.LDAP).hasItem(UserExtension.SITE, "Prague");
t.target(Resources.LDAP).account(dn).hasAttribute(LdapAttrs.OU, "Sales");
```

Generated classes: `Resources`, `Roles`, `Orgs`, `Services`, `Archetypes`, `Users` (users the configuration holds),
`Tasks`, `Reports`, `CertificationDefinitions`, `<Resource>Attrs` per resource (its `schemaHandling` attributes, a stored
schema's attributes, a CSV connector's unique/name/password columns, the header of a CSV file of initial data, the
attributes of an LDIF of initial data), `UserExtension` (items of `config.schema` that extend users) and `Names` (all
of them). The names are those of every line together: a name only some lines have (an overlay adds, replaces or
removes its file) carries its lines (`Reports.USERS_BY_COST_CENTER.existsOn(t.line())`), and using it on another
line fails with a clear message. So shared
test sources compile on every line. An attribute of another resource (`account.hasAttribute(HrAttrs.FIRST_NAME)` on
LDAP) fails at once. Two names that would make the same constant (`Dept-Sales`, `Dept Sales`) fail the generation.

At load, every generated name of the line is checked against the loaded configuration (objects by name and OID,
attributes in the schema midPoint stored for the resource, extension items in midPoint's schema): generated classes of
an older configuration fail the load with the list of what is missing.

The generator is a main class of the tester artifact (no midPoint needed: plain XML and YAML), run by
exec-maven-plugin; it searches the given directories for `midpoint-config-tester.yaml` with `names.package`, writes a
file only when its content changes and deletes generated files no longer produced. The parents of
[Getting started](../../README.md#getting-started) configure it; a project without them adds:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.0.0</version>
  <executions>
    <execution>
      <?m2e execute onConfiguration,onIncremental?>
      <id>midpoint-names</id>
      <phase>generate-sources</phase>      <!-- the IDE's "generate sources" runs it too -->
      <goals><goal>java</goal></goals>
      <configuration>
        <mainClass>com.github.wadahiro.midpoint.configtester.names.NamesGenerator</mainClass>
        <classpathScope>test</classpathScope>
        <arguments>
          <argument>${project.build.directory}/generated-test-sources/midpoint-names</argument>
          <argument>${project.basedir}/src/test/resources</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>build-helper-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>midpoint-names-source</id>
      <phase>generate-test-sources</phase>
      <goals><goal>add-test-source</goal></goals>
      <configuration>
        <sources><source>${project.build.directory}/generated-test-sources/midpoint-names</source></sources>
      </configuration>
    </execution>
  </executions>
</plugin>
```

(The parents configure it; a repository without the parent adds the plugin as above.)

**midPoint's own names** are shipped in `com.github.wadahiro.midpoint.configtester.names.midpoint`, with the lines
that have them:

| Class | Names |
|---|---|
| `InitialUsers`, `InitialRoles`, `InitialArchetypes`, `InitialOrgs`, `InitialServices` | `ADMINISTRATOR`; `END_USER`, `SUPERUSER`, `APPROVER`, `REVIEWER`, `DELEGATOR`; `SYSTEM_USER`, `BUSINESS_ROLE`, `APPROVAL_CASE`, `PERSON`...; the orgs of 4.10; `INTERNAL`... |
| `InitialTasks`, `InitialReports` | `VALIDITY_SCANNER`, `TRIGGER_SCANNER`, `CLEANUP`; `RECONCILIATION_REPORT`, `USERS_IN_MIDPOINT`... |
| `InitialCollections`, `InitialDashboards`, `InitialPolicies`, `InitialMarks`, `InitialTemplates` | `MidPointObjectRef`s (name, OID, type) of types no verb takes by name: `ALL_TASKS`; `ADMIN_DASHBOARD`; `DEFAULT_SECURITY_POLICY`, `DEFAULT_PASSWORD_POLICY`; the marks (4.8 and later); `PERSON_OBJECT_TEMPLATE` |
| `UserItems` | items of midPoint's user schema for `hasItem`, `values` and `why`: `NAME`, `FULL_NAME`, `GIVEN_NAME`, `FAMILY_NAME`, `EMAIL_ADDRESS`, `TELEPHONE_NUMBER`, `EMPLOYEE_NUMBER`, `COST_CENTER`, `LOCALITY`, `TITLE`, `ORGANIZATION`, `ORGANIZATIONAL_UNIT`, `LIFECYCLE_STATE`, `ADMINISTRATIVE_STATUS` (`activation/administrativeStatus`), `VALID_FROM`, `VALID_TO`, `EFFECTIVE_STATUS`...; `PERSONAL_NUMBER` (4.8 and later), `DISPLAY_NAME` (4.11 and later) |

```java
t.user(Users.JACK).hasAssignment(InitialRoles.END_USER).hasItem(UserItems.COST_CENTER, "IT");
t.midpoint().find(ValuePolicyType.class, InitialPolicies.DEFAULT_PASSWORD_POLICY.oid());
```

A name whose object differs between lines gets one constant per name (`InitialReports.CERTIFICATION_DECISIONS_REPORT`
on 4.4, `CERTIFICATION_WORK_ITEMS_REPORT` on 4.8+, the same OID). Each name is checked on every line: it resolves
after the initial import, and each user item is in the line's schema exactly on its lines.
