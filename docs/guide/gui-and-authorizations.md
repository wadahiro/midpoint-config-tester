# The GUI and authorizations

## What the GUI shows a user

What a user sees and may change in the GUI is decided by their authorizations, and asked the same way as a request,
logged in as them:

```java
// the objects a search finds (the GUI's lists): midPoint narrows it by the user's #read authorizations
assertThat(t.actor(Users.HELPDESK).search(UserType.class))
        .containsExactlyInAnyOrder(Users.JACK, Users.ROLE_OWNER, Users.HELPDESK);
assertThat(t.actor(Users.HELPDESK).canSearch(Users.ANN)).isFalse();   // #search: the list shows it
assertThat(t.actor(Users.HELPDESK).canGet(Users.ANN)).isTrue();    // #get: its details open
assertThat(t.actor(Users.HELPDESK).canRead(Users.JACK)).isTrue();  // #read: both

// a page opens, as the GUI decides it: by its URL, or by the actions of its @PageDescriptor
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/users")).isTrue();
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/user/" + Users.JACK.oid())).isTrue();   // #userDetails
assertThat(t.actor(Users.HELPDESK).canOpen(AuthorizationConstants.AUTZ_UI_USERS_ALL_URL,
        AuthorizationConstants.AUTZ_UI_USERS_URL, AuthorizationConstants.AUTZ_UI_USERS_VIEW_URL)).isTrue();

// an object's form: what it shows and what it lets the user change
Form form = t.actor(Users.HELPDESK).form(Users.JACK);
assertThat(form.modifiableItems()).containsExactly("telephoneNumber");
assertThat(form.canRead("emailAddress")).isTrue();
```

- The names follow midPoint's actions: `#search` lets a search find an object, `#get` lets the user get it by its OID,
  `#read` allows both. `search(type)` searches as the user through `ModelService` (all objects of the type, as the
  GUI's list does before its own filters) and gives typed refs, equal to the generated names; `canSearch(object)`
  is whether it finds the object, `canGet(object)` whether getting it succeeds, `canRead(object)` both.
- `collection(name)` is an object collection's list as the user sees it (a "my requests" page): the collection's
  definition read and compiled privileged, as the GUI does, its filter's expressions evaluated for the user
  (`midpoint.principalOid`), its base collection, then searched as the user.
- `canOpen(url)` decides a URL as the GUI's authorization evaluator does: the path after the GUI's context, matched
  (case-insensitive Ant patterns) against every page's `@PageDescriptor` and the GUI's own URL mappings
  (`EndPointsUrlMapping` on 4.8, `PageUrlMapping` on 4.4), the actions of all that match together, with
  `#guiAll`; a page any user opens (login pages, `permitAll`) is allowed, a URL no page matches is denied. The patterns
  overlap as in the GUI: `/admin/user/**` (a user's details, `#userDetails`) also matches the new user page
  `/admin/user`. `canOpen(actions...)` (more than one argument, or one not starting with `/`) takes a page's actions
  instead (`PageUsers`: `#usersAll`, `#users`, `#usersView`). Either way the security enforcer decides
  (`decideAccess`): one allowed and none denied. A failed assertion shows what the URL matched and needed.
- The tester knows the line's GUI pages from an index it ships; the GUI itself is not on the test classpath.
- `form(object)` is the object's definition as the GUI's form gets it (`getEditObjectDefinition`, request phase):
  `canRead(path)`, `canModify(path)`, and the top-level `readableItems()` and `modifiableItems()`; `opens()` is false
  when the user may not read the object at all.
- A failed assertion after any of them lists the user's authorizations, by the role they come from:
  `'read-it-users': allow authorization-model-3#read of UserType with a filter; items name, givenName...`.
- midPoint's initial End user role (every user of the sample has it) lets a user get **any** user, with all items
  (`assignment-target-get`, for the GUI's selection windows): a search finds only what `#read` allows, but every user's
  details open for everyone, and a narrower `#read` shows no more than that. The test shows it; a deployment that
  keeps the role as shipped has it too.
- The execution phase is not asked on its own: a request or a change through the API goes through both
  phases, as in the GUI.

The GUI's configuration (`adminGuiConfiguration`) is asked the same way: `guiProfile()` is the user's GUI profile as
midPoint compiled it at login:

```java
GuiProfile manager = t.actor(Users.IT_MANAGER).guiProfile();
assertThat(manager.views(UserType.COMPLEX_TYPE)).extracting(GuiProfile.CollectionView::identifier)
        .containsExactlyInAnyOrder("people", "department-members");   // the system's, the induced role's
assertThat(manager.view(UserType.COMPLEX_TYPE, "department-members").inMenu()).isTrue();
assertThat(manager.featureVisibility("orgTreeExpandAll")).isEqualTo(UserInterfaceElementVisibilityType.HIDDEN);
assertThat(manager.defaultTimezone()).isEqualTo("Asia/Tokyo");
```

- midPoint merges the system configuration's `adminGuiConfiguration`, then each one of the roles, orgs and archetypes
  the user has (assigned or induced, with their conditions), by its rules: a later time zone wins, a hidden feature
  stays hidden. `guiProfile()` is that result (`CompiledGuiProfile`), not a merge of the tester's own: the object collection views (`views()`, by type), the
  additional menu links, the dashboard links, the features, the details pages' configuration (`objectDetails()`),
  the time zone and the data language; `compiled()` is midPoint's object for the rest.
- `inMenu()` is whether the line's side menu lists a view under its type, by the line's rule: on 4.8, not the
  default view, not hidden or vacant, for no operation or modify (4.4: the same with a collection, the default view
  too). The menu item's own page
  (the users list) is `canOpen`. The rest of the menu (which sections and pages it shows) is not modeled.
- A failed assertion after it shows the user's views, features and links.

All of these for kinds of users at once, as a matrix: [An authorization matrix](authz-matrix.md) (experimental).
