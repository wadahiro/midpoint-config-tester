#!/usr/bin/env python3
"""Writes the typed names of midPoint's initial objects (configtester.names.midpoint.Initial*) from the initial
objects of every supported line.

    initial-names.py <output dir> <line>=<jar> ...

<jar> is the jar that holds the line's initial-objects/: admin-gui-<v>-classes.jar for 4.0 and 4.4, system-init-<v>.jar
for 4.8+ (all in ~/.m2 once the line modules have been built). Every name carries the lines whose jar has it with that
name (Lines.ALL when all given lines have it). The constants follow NamesGenerator.constant; CONSTANTS overrides a few,
SKIP leaves out what the tester does not import or that only differs by a typo. InitialNamesTest checks the result on
every line: each name of the line resolves after the initial import, and each initial object of these types has one.
Run it from a script directory, not from inside a jar's directory (the jars are only read as zip files).
"""
import collections
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.github.wadahiro.midpoint.configtester.names.midpoint"

# type element -> (class, ref class, constructor prefix, description)
GROUPS = {
    "user": ("InitialUsers", "UserRef", "", "users"),
    "role": ("InitialRoles", "RoleRef", "", "roles"),
    "archetype": ("InitialArchetypes", "ArchetypeRef", "", "archetypes"),
    "org": ("InitialOrgs", "OrgRef", "", "orgs"),
    "service": ("InitialServices", "ServiceRef", "", "services"),
    "task": ("InitialTasks", "TaskRef", "", "tasks"),
    "report": ("InitialReports", "ReportRef", "", "reports"),
    "objectCollection": ("InitialCollections", "MidPointObjectRef", '"ObjectCollectionType", "object collection", ',
                         "object collections"),
    "dashboard": ("InitialDashboards", "MidPointObjectRef", '"DashboardType", "dashboard", ', "dashboards"),
    "securityPolicy": ("InitialPolicies", "MidPointObjectRef", '"SecurityPolicyType", "security policy", ',
                       "security, value and (4.9+) policy objects"),
    "valuePolicy": ("InitialPolicies", "MidPointObjectRef", '"ValuePolicyType", "value policy", ', None),
    "policy": ("InitialPolicies", "MidPointObjectRef", '"PolicyType", "policy", ', None),
    "mark": ("InitialMarks", "MidPointObjectRef", '"MarkType", "mark", ', "marks (4.8+)"),
    "objectTemplate": ("InitialTemplates", "MidPointObjectRef", '"ObjectTemplateType", "object template", ',
                       "object templates"),
}

# (oid, name) -> constant, where NamesGenerator.constant reads badly
CONSTANTS = {
    ("00000000-0000-0000-0000-000000000110", "Users in MidPoint"): "USERS_IN_MIDPOINT",
    ("00000000-0000-0000-0000-000000000517",
     "Execute change task (one delta applicable for many objects)"): "EXECUTE_CHANGE_TASK",
    ("00000000-0000-0000-0000-000000000518",
     "Execute deltas task (background processing for change execution)"): "EXECUTE_DELTAS_TASK",
}

# (oid, name) or (type, line) left out, with the reason
SKIP = {
    # 4.4's name has two typos, 4.8 fixed them: EXECUTE_DELTAS_TASK (4.8+) only
    ("00000000-0000-0000-0000-000000000518", "Execute deltas task (backgroud processing for chnage execution)"),
}
# the tester skips 4.0's Jasper reports (no Jasper libraries): no report exists on 4.0
NOT_IMPORTED = {("report", "4.0")}


def constant(name):
    out, previous = [], ""
    for c in name:
        if c.isalnum() and c.isascii():
            if c.isupper() and (previous.islower() or previous.isdigit()):
                out.append("_")
            out.append(c.upper())
        elif out and out[-1] != "_":
            out.append("_")
        previous = c
    s = "".join(out).rstrip("_")
    return "_" + s if not s or s[0].isdigit() else s


def literal(value):
    out = ['"']
    for c in value:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif ord(c) < 0x20 or ord(c) > 0x7e:
            out.append("\\u%04x" % ord(c))
        else:
            out.append(c)
    return "".join(out) + '"'


def objects(jar):
    with zipfile.ZipFile(jar) as z:
        for entry in sorted(z.namelist()):
            if not entry.startswith("initial-objects/") or not entry.endswith(".xml"):
                continue
            root = ET.fromstring(z.read(entry))
            for o in ([root] if not root.tag.endswith("}objects") else list(root)):
                kind = o.tag.split("}")[-1]
                name = None
                for c in o:
                    if c.tag.split("}")[-1] == "name":
                        orig = [g for g in c if g.tag.split("}")[-1] == "orig"]
                        name = ("".join(orig[0].itertext()) if orig else "".join(c.itertext())).strip()
                        break
                yield kind, o.get("oid"), name, entry[len("initial-objects/"):]


def main():
    out_dir, specs = sys.argv[1], sys.argv[2:]
    lines = [s.split("=", 1)[0] for s in specs]
    names = collections.OrderedDict()  # (kind, oid, name) -> {lines}, files
    for spec in specs:
        line, jar = spec.split("=", 1)
        for kind, oid, name, file in objects(jar):
            if kind not in GROUPS or (oid, name) in SKIP or (kind, line) in NOT_IMPORTED:
                continue
            entry = names.setdefault((kind, oid, name), [set(), set()])
            entry[0].add(line)
            entry[1].add(file.split("/")[-1])
    classes = collections.defaultdict(dict)
    for (kind, oid, name), (on, files) in names.items():
        cls, ref, prefix, _ = GROUPS[kind]
        const = CONSTANTS.get((oid, name), constant(name))
        if const in classes[cls]:
            raise SystemExit(f"{cls}.{const} would name both {classes[cls][const][2]} and {name}")
        avail = "Lines.ALL" if len(on) == len(lines) else \
            "Lines.only(" + ", ".join(literal(l) for l in lines if l in on) + ")"
        classes[cls][const] = (ref, prefix, name, oid, avail, sorted(files), [l for l in lines if l in on])
    os.makedirs(out_dir, exist_ok=True)
    docs = {g[0]: g[3] for g in GROUPS.values() if g[3]}
    for cls, consts in sorted(classes.items()):
        src = [f"// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of "
               f"midPoint {', '.join(lines)}.",
               "// Do not edit: run the script again (InitialNamesTest checks it on every line).",
               f"package {PACKAGE};", "",
               "import com.github.wadahiro.midpoint.configtester.names.*;", "",
               "/**",
               f" * The {docs[cls]} midPoint imports on its first start (its initial objects), on the lines that ship them",
               " * under this name. Like the generated names of a deployment, usable in every verb that takes a name.",
               " */",
               f"public final class {cls} {{", ""]
        for const, (ref, prefix, name, oid, avail, files, on) in sorted(consts.items()):
            src.append(f"    /** \"{name}\", {', '.join(files)} ({', '.join(on)}). */")
            src.append(f"    public static final {ref} {const} = new {ref}({prefix}{literal(name)}, {literal(oid)}, {avail});")
            src.append("")
        src += [f"    private {cls}() {{", "    }", "}", ""]
        with open(os.path.join(out_dir, cls + ".java"), "w") as f:
            f.write("\n".join(src))
    print({cls: len(c) for cls, c in classes.items()})


if __name__ == "__main__":
    main()
