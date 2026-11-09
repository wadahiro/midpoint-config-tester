#!/usr/bin/env bash
# Prints the midPoint API the tester (tester/) depends on, for one midPoint source tree.
# Run it on the new line and on each supported neighbour, all at their release tags (a support branch head mixes in
# post-release changes), then diff the outputs:
#   probe-api.sh ~/src/midpoint-4.10.3 > probe-4.10.txt; probe-api.sh ~/src/midpoint-4.11.0 > probe-4.11.txt
#   diff probe-4.10.txt probe-4.11.txt
# Declarations are printed whole (up to "{" or ";") and without line numbers, so the diff shows only real changes;
# --lines adds the line numbers. prism classes the tree does not have (4.4 takes prism as a separate artifact) are read
# with javap from the prism-api jar in ~/.m2. A section is grouped by the tester class that needs it (see
# docs/line-anatomy.md); "(not in this tree)" is an answer too (e.g. CaseService before 4.5).
set -u
LINES=0
if [ "${1:-}" = "--lines" ]; then
    LINES=1
    shift
fi
SRC="${1:?usage: probe-api.sh [--lines] <midpoint source dir>}"

# main-source files of that name, keeping those whose path contains the hint (e.g. repo-common) when one is given
find_class() {
    local class="$1" hint="${2:-}"
    local files
    files=$(find "$SRC" -name "$class.java" -path '*/src/main/*' -not -path '*/target/*' 2>/dev/null)
    if [ -n "$hint" ]; then
        files=$(echo "$files" | grep -- "$hint")
    fi
    echo "$files" | grep -v '^$'
}

# prints the matching declarations of a file, each joined up to its "{" or ";"
declarations() {
    local file="$1" pattern="$2"
    # the pattern goes through the environment: awk -v would turn "\(" into "("
    PATTERN="$pattern" awk -v lines="$LINES" '
        function emit() {
            sub(/[ \t]*\{.*$/, "", text)
            printf "   %s%s\n", (lines ? start ": " : ""), substr(text, 1, 220)
            collecting = 0
        }
        collecting {
            line = $0
            sub(/^[ \t]+/, "", line)
            text = text " " line
            if (line ~ /[{;]/) { emit() }
            next
        }
        # comments are not declarations (a javadoc may mention a method by name)
        /^[ \t]*(\*|\/\/|\/\*)/ { next }
        $0 ~ ENVIRON["PATTERN"] {
            start = NR
            text = $0
            sub(/^[ \t]+/, "", text)
            if (text ~ /[{;]/) { emit(); next }
            collecting = 1
        }
    ' "$file"
}

# prism classes not in the tree: javap on the prism-api jar of the line's prism version, if it is in ~/.m2
PRISM_VERSION=""
prism_section() {
    local class="$1" pattern="$2" jar
    case "$class" in DefinitionFactory | ParsingContext | PrismContext | PrismPropertyDefinition) ;; *) return ;; esac
    [ -n "$PRISM_VERSION" ] || return
    local repo="$HOME/.m2/repository/com/evolveum/prism/prism-api" version="$PRISM_VERSION"
    jar="$repo/$version/prism-api-$version.jar"
    if [ ! -f "$jar" ]; then
        # a support branch is a SNAPSHOT: take the newest release of the same line that is in ~/.m2
        version=$(ls "$repo" 2>/dev/null | grep -E "^${PRISM_VERSION%.*}\.[0-9]+$" | sort -t. -k3 -n | tail -1)
        jar="$repo/$version/prism-api-$version.jar"
    fi
    if [ -z "$version" ] || [ ! -f "$jar" ]; then
        echo "   (prism-api $PRISM_VERSION not in ~/.m2: resolve the line's dependencies first, or"
        echo "    mvn dependency:get -Dartifact=com.evolveum.prism:prism-api:$PRISM_VERSION, then probe again)"
        return
    fi
    echo "   (javap of prism-api-$version.jar)"
    javap -cp "$jar" "com.evolveum.midpoint.prism.$class" 2>/dev/null | grep -E "$pattern" | sed 's/^ */   /'
}

section() {
    local class="$1" pattern="$2" hint="${3:-}"
    local files count file
    files=$(find_class "$class" "$hint")
    count=$(echo "$files" | grep -c . || true)
    echo "== $class"
    if [ "$count" = 0 ]; then
        echo "   (not in this tree)"
        prism_section "$class" "$pattern"
        return
    fi
    file=$(echo "$files" | head -1)
    echo "   ${file#"$SRC"/}"
    if [ "$count" -gt 1 ]; then
        echo "   (warning: $count files named $class.java; give the section a path hint to pick another)"
    fi
    declarations "$file" "$pattern"
}

echo "# midPoint API probe of $SRC"
echo "== versions"
# the project's own version: the first <version> after the parent block, or the first one without a parent
MP_VERSION=$(if grep -q '</parent>' "$SRC/pom.xml"; then
    awk '/<\/parent>/{p=1; next} p && /<version>/{print; exit}' "$SRC/pom.xml"
else
    grep -m1 '<version>' "$SRC/pom.xml"
fi | sed -E 's/.*<version>([^<]+)<.*/\1/')
echo "   midPoint $MP_VERSION"
# library and Java versions: the root POM, or build-system/pom.xml up to 4.4. The JDK the line needs is its
# project.source.version / maven.compiler.release / java.version.
for pom in "$SRC/pom.xml" "$SRC/build-system/pom.xml"; do
    [ -f "$pom" ] && grep -E '<(groovy\.version|java\.version|project\.source\.version|maven\.compiler\.release|maven\.compiler\.source|spring\.version|spring-boot\.version|prism\.version|prism-api\.version|connid\.version|junit\.version)>' "$pom"
done | sed 's/^ */   /' | sort -u
PRISM_VERSION=$(grep -hE '<prism(-api)?\.version>' "$SRC/pom.xml" "$SRC/build-system/pom.xml" 2>/dev/null \
    | head -1 | sed -E 's/.*>([^<]+)<.*/\1/')
case "$PRISM_VERSION" in *'${'*) PRISM_VERSION="" ;; esac
# without a property (4.4), prism is released with the same version as midPoint
if [ -z "$PRISM_VERSION" ]; then
    PRISM_VERSION="$MP_VERSION"
fi

# ---- recorder: mapping hook (RecorderInstaller, RecordingMappingFactory, RecordingMappingImpl, Recorder<XY>) ----
# 4.4+: the mappingFactory bean becomes RecordingMappingFactory, whose builder builds RecordingMappingImpl (a subclass
# in midPoint's package: MappingImpl's constructor and fields are package-private). 4.0: MappingImpl cannot be
# subclassed, the mappingEvaluator bean becomes RecordingMappingEvaluator. Recorder<XY> reads the fields by name.
section MappingFactory 'class MappingFactory|public .*(createMappingBuilder|setBeans|isProfiling)\(' model-common
section MappingBuilder 'class MappingBuilder|public .* build\(|MappingBuilder\(\)' model-common
section MappingImpl 'class MappingImpl|MappingImpl\(|public static .*Builder|public .* (evaluate|clone)\(|(ExpressionVariables|VariablesMap) variables;|List<Source.*> sources|ItemPath outputPath;' model-common
section AbstractMappingImpl 'class AbstractMappingImpl|Boolean conditionResult(Old|New);|> mappingConfigItem;|(VariablesMap|ExpressionVariables) variables;|List<Source.*> sources|public .* (getMappingQName|getTargetItemName|getOriginObject|evaluate)\(' model-common
section MappingEvaluator 'class MappingEvaluator|public .* evaluateMapping\(' model-impl
section ConfigurationItemOrigin 'class ConfigurationItemOrigin|class InObject|getOriginatingPrismObject\(|getPath\(' schema
section ModelBeans 'mappingFactory;' model-impl

# ---- recorder: expression hook (EvaluatorHook: JDK proxies of the evaluator factories, fields read by name) ----
section ExpressionFactory 'class ExpressionFactory|Map<.*> evaluatorFactoriesMap|ExpressionEvaluatorFactory defaultEvaluatorFactory;|public .* makeExpression\(' repo-common
section ExpressionEvaluatorFactory 'createEvaluator\(|getElementName\(' repo-common
section ExpressionEvaluator 'evaluate\(|default .*\(' repo-common
section ExpressionEvaluationContext 'public .* get(ContextDescription|LocalContextDescription|Variables|Sources|SkipEvaluation(Plus|Minus))\(' repo-common

# ---- recorder: scripts outside an ExpressionFactory (ScriptHook48 / ScriptHook40: evaluatorMap, scriptBean) ----
section ScriptExpressionFactory 'class ScriptExpressionFactory|Map<.*> evaluatorMap|public .*(registerEvaluator|setEvaluators|createScriptExpression)\(' model-common
section ScriptEvaluator 'interface ScriptEvaluator|evaluate\(|getLanguageUrl\(' model-common
section ScriptExpressionEvaluationContext 'scriptBean;|ScriptExpressionEvaluatorType scriptType|get(ScriptBean|ScriptType|ExpressionType|ContextDescription|Variables)\(' model-common
echo "== hook script descriptions (ScriptHook*.hookName: \"hook '<name>'\", \"scripting hook in system configuration\")"
for f in $(find "$SRC/model/model-impl/src/main" -name 'ClockworkHookHelper.java' -o -name 'Clockwork.java' 2>/dev/null); do
    grep -nE 'shortDesc = "|"scripting hook' "$f" | sed -E "s|^[0-9]+:[ \t]*|   ${f#"$SRC"/}: |"
done
section ReportServiceImpl 'public .* evaluateScript\(|getObjectVariableMode|setObjectVariableMode' report-impl

# ---- harness (Harness<XY>: model-intest base class, start, account import, reset) ----
section AbstractModelIntegrationTest 'public .* (initSystem|importAccountsRequest|login|findUserByUsername|createTask|initSystemConditional)\('
section AbstractIntegrationTest 'public .* (initSystem|initSystemConditional|createTask|login)\(|@PostConstruct'
section MidpointTestContextWithTask 'class MidpointTestContextWithTask|public static .*\('
section ModelService 'testResource\(|importFromResource\(|postInit\(' model-api
section CacheDispatcher 'dispatchInvalidation\('
section SqaleRepoContext 'clearCaches\(|ExtItemCache extItemCache'
section ExtItemCache 'class ExtItemCache|> nameToExtItem =|void initialize\('
section ExtItemDictionary 'class ExtItemDictionary|public .* initialize\('
section ResourceCache 'class ResourceCache|(private|final) .* cache( =|;)' provisioning-impl
section TaskManager 'registerTaskListener\(|getNextRunStartTime\(|synchronizeTasks\(|suspendTasks\(|switchToBackground\(' task-api
section TaskListener 'interface TaskListener|void [a-zA-Z]+\(' task-api
section TaskManagerQuartzImpl 'public .* getExecutionManager\('
section ExecutionManager 'public .* getQuartzScheduler\(' task-quartz-impl
section Clock 'public .* (override[A-Za-z]*|resetOverride|currentTimeMillis|currentTimeXMLGregorianCalendar)\('

# ---- notifications (RecordingTransport, Harness<XY>.initSystem) ----
section DummyTransport 'class DummyTransport|NAME =|public .* (send|getMessages|clearMessages)\('
section NotificationManager 'setDisabled\(|registerTransport\(' notifications-api
section TransportService 'registerTransport\(' notifications-api

# ---- drivers (Drivers<XY>, ApprovalsImpl, CertificationImpl, ReportsImpl, DashboardsImpl, TimeImpl) ----
section ModelInteractionService 'previewChanges\(|public .* submit\(|validateValue\(|generateValue\(' model-api
section ModelContext 'getHookPreviewResults\(' model-api
section CaseService 'completeWorkItem\(|delegateWorkItem\(|claimWorkItem\(|cancelCase\(' model-api
section WorkflowService 'completeWorkItem\(|delegateWorkItem\(' model-api
section AccessCertificationService 'createCampaign\(|openNextStage\(|closeCurrentStage\(|startRemediation\(|closeCampaign\(|recordDecision\(' model-api
section CertificationManager 'createCampaign\(|openNextStage\(|closeCurrentStage\(|startRemediation\(|closeCampaign\(|recordDecision\(' certification-api
section ReportManager 'runReport\(' report-api
section ReportTestUtil 'public static .* findOutputFile\('
section ActivitySubmissionOptions 'class ActivitySubmissionOptions|public static .* create\(|public .* with[A-Za-z]*\('
section DashboardService 'createWidgetData\(' model-api
section SecurityEnforcer ' isAuthorized\(' security-enforcer-api
section BulkActionsService 'executeBulkAction\(' model-api
section ScriptingService 'evaluateExpression\(' model-api
section ValuePolicyProcessor 'public .* (validateValue|generate[A-Za-z]*)\(' model-common
section ExpressionConstants 'OUTPUT_ELEMENT_NAME =|VAR_(FOCUS|PROJECTION|SHADOW|ACCOUNT|ASSOCIATION|OBJECT|INPUT|CONFIGURATION|THIS_OBJECT|ORDER_ONE_OBJECT) ='

# ---- prism API (LineSupport.outputDefinition, serializeRealValue; javap of prism-api where prism is not in the tree) ----
section DefinitionFactory 'create(Property|Reference)Definition|new(Property|Reference)Definition'
section PrismPropertyDefinition 'toMutable\(|mutator\('
section PrismContext 'static .* get\(|jsonSerializer\(|xmlSerializer\(|createParsingContextForCompatibilityMode'
section ParsingContext 'strict\(|compat\(|getWarnings'

# ---- static checks (tester/static: StaticLine) ----
section MidPointPrismContextFactory 'public MidPointPrismContextFactory\(|createInitializedPrismContext\('
section ObjectUpgradeValidator 'public ObjectUpgradeValidator\(|public .* validate\(|showAllWarnings'
section ValidationItem 'class ValidationItem|record ValidationItem|public .* get(Status|Message|ItemPath)\('

echo "== evaluator elements of the xsd (substitution group c:expressionEvaluator; StaticCheckTest.evaluatorsOfTheLine"
echo "   pins the set StaticLine.evaluators reads from prism, which leaves out projectionDiscriminator)"
grep -rh -B4 'substitutionGroup="[a-z]*:expressionEvaluator"' "$SRC/infra/schema/src/main/resources" --include='*.xsd' \
    2>/dev/null | grep -o '<xsd:element name="[A-Za-z]*"' | sed -E 's/.*name="([A-Za-z]*)"/\1/' \
    | grep -vx expressionEvaluator | sort | sed 's/^/   /'

# ---- repository, SQL, Spring contexts, baseline objects ----
echo "== repositories and SQL"
ls -d "$SRC"/repo/repo-sqale "$SRC"/repo/repo-sql-impl 2>/dev/null | sed "s|$SRC/|   |"
(cd "$SRC/config/sql" 2>/dev/null && find . -name '*.sql' | sort | sed 's|^\./|   config/sql/|' | head -30)
echo "== generated columns of the abstract tables (PostgreSQL 16+ needs them generated in the parent too)"
for f in $(find "$SRC/config/sql" -name 'postgres*.sql' -not -name '*audit*' -not -name '*quartz*' -not -name '*upgrade*' 2>/dev/null | sort); do
    grep -nE '(objectType|containerType|referenceType)[ \t]+[A-Za-z]+.*GENERATED|CREATE TABLE m_assignment[ (]' "$f" \
        | grep -vE '^[0-9]+:[ \t]*--' | sed -E "s|^[0-9]+:[ \t]*|   ${f#"$SRC"/}: |" | head -6
done
echo "== repository type of midPoint's own test configurations"
find "$SRC/repo" -name 'test-config*.xml' -not -path '*/target/*' 2>/dev/null | sort | while read -r f; do
    echo "   ${f#"$SRC"/}: $(grep -oE '<type>[^<]+</type>|<repositoryServiceFactoryClass>[^<]+<' "$f" | head -1 | sed 's/<$//')"
done
echo "== how the test configuration file is chosen (ctx-configuration-test.xml)"
find "$SRC" -name 'ctx-configuration-test.xml' -not -path '*/target/*' 2>/dev/null | head -1 | while read -r f; do
    grep -oE '(test\.config\.file|test-config[^"<]*\.xml)' "$f" | sort -u | sed 's/^/   /'
done
echo "== Spring contexts the tester imports (tester/ctx-tester.xml) besides model-intest's"
for ctx in ctx-cases.xml ctx-workflow.xml ctx-certification.xml ctx-report.xml; do
    f=$(find "$SRC" -name "$ctx" -path '*/src/main/resources/*' -not -path '*/target/*' 2>/dev/null | head -1)
    if [ -n "$f" ]; then echo "   $ctx: ${f#"$SRC"/}"; else echo "   $ctx: (none)"; fi
done
echo "== Spring test contexts (model-intest)"
grep -h 'import resource' "$SRC"/model/model-intest/src/test/resources/ctx-model-intest-test-main.xml 2>/dev/null \
    | sed 's/^ */   /' | awk '!seen[$0]++'
echo "== initial objects the baseline copies (LineSupport.BASELINE: 021, 023-025, 041, 042, 507, 511, 520, 521, 530, 531)"
# config/initial-objects up to 4.8; repo/system-init/src/main/resources/initial-objects from 4.9 (4.9: config/ keeps only a
# README; 4.10 has copies in both)
find "$SRC/config/initial-objects" "$SRC/repo/system-init/src/main/resources/initial-objects" -name '*.xml' 2>/dev/null \
    | grep -E '/(021|023|024|025|041|042|507|511|520|521|530|531)-[^/]*\.xml$' | sort | sed "s|$SRC/|   |"
