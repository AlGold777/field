#!/usr/bin/env python3
"""Генерирует ios/SoundField.xcodeproj/project.pbxproj из файлов в ios/SoundField/.
Запуск: python3 tools/gen_xcodeproj.py (после добавления/удаления файлов)."""
import hashlib, os, re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'ios')
SRC = os.path.join(ROOT, 'SoundField')
DSP = os.path.normpath(os.path.join(ROOT, '..', 'dsp'))   # C++-ядро: группа dsp с подгруппами src и include
DSP_SUBDIRS = ('src', 'include')                            # остальные папки dsp (build, lab, tests, jni) не попадают в проект
PROJECT_YML = os.path.join(ROOT, 'project.yml')
with open(PROJECT_YML, encoding='utf-8') as source:
    project_config = source.read()
MARKETING_VERSION = re.search(r'(?m)^\s*MARKETING_VERSION:\s*(\S+)', project_config).group(1)
CURRENT_PROJECT_VERSION = re.search(r'(?m)^\s*CURRENT_PROJECT_VERSION:\s*(\S+)', project_config).group(1)

def oid(key):
    return hashlib.md5(key.encode()).hexdigest()[:24].upper()

groups = {}   # key (rel dir) -> (display name, path attribute, list of (name, kind))
swift, resources, cpp = [], [], []
for dirpath, dirnames, filenames in os.walk(SRC):
    rel = os.path.relpath(dirpath, SRC)
    dirnames[:] = sorted(d for d in dirnames if not d.endswith('.xcassets'))
    entries = []
    for d in sorted(os.listdir(dirpath)):
        full = os.path.join(dirpath, d)
        if d.startswith('.'): continue
        if os.path.isdir(full) and not d.endswith('.xcassets'):
            entries.append((d, 'group'))
        elif d.endswith('.swift'):
            entries.append((d, 'swift')); swift.append(os.path.normpath(os.path.join(rel, d)))
        elif d.endswith('.xcassets'):
            entries.append((d, 'assets')); resources.append(os.path.normpath(os.path.join(rel, d)))
        elif d.endswith('.wav'):
            entries.append((d, 'wav')); resources.append(os.path.normpath(os.path.join(rel, d)))
        elif d == 'Info.plist':
            entries.append((d, 'plist'))
        elif d.endswith('.h'):
            entries.append((d, 'header'))
    key = os.path.normpath(rel)
    groups[key] = ('SoundField' if key == '.' else os.path.basename(key), os.path.basename(key) if key != '.' else 'SoundField', entries)

# dsp: только src (.cpp компилируются, .hpp — ссылки) и include (.h); без рекурсии, остальные папки игнорируются
dsp_root = []
for sub in DSP_SUBDIRS:
    if os.path.isdir(os.path.join(DSP, sub)):
        dsp_root.append((sub, 'group'))
groups['dsp'] = ('dsp', '../dsp', dsp_root)

# profiles: единый источник данных профилей устройств (A2), кладётся в бандл как ресурс
PROFILES_DIR = os.path.normpath(os.path.join(ROOT, '..', 'profiles'))
PROFILES_FILES = ('devices.json',)
if all(os.path.isfile(os.path.join(PROFILES_DIR, f)) for f in PROFILES_FILES):
    groups['profiles'] = ('profiles', '../profiles', [(f, 'json') for f in PROFILES_FILES])
    resources += [os.path.join('profiles', f) for f in PROFILES_FILES]
for sub in DSP_SUBDIRS:
    full_dir = os.path.join(DSP, sub)
    if not os.path.isdir(full_dir):
        continue
    entries = []
    for d in sorted(os.listdir(full_dir)):
        if d.startswith('.') or not os.path.isfile(os.path.join(full_dir, d)):
            continue
        if sub == 'src' and d.endswith('.cpp'):
            entries.append((d, 'cpp')); cpp.append(os.path.join('dsp', sub, d))
        elif sub == 'src' and d.endswith('.hpp'):
            entries.append((d, 'cpph'))
        elif sub == 'include' and d.endswith('.h'):
            entries.append((d, 'cheader'))
    groups['dsp/' + sub] = (sub, sub, entries)

ftype = {'swift': 'sourcecode.swift', 'assets': 'folder.assetcatalog', 'wav': 'audio.wav', 'plist': 'text.plist.xml',
         'header': 'sourcecode.c.h', 'cpp': 'sourcecode.cpp.cpp', 'cpph': 'sourcecode.cpp.h', 'cheader': 'sourcecode.c.h',
         'json': 'text.json'}
L = []
w = L.append
w('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 56;\n\tobjects = {\n')

w('\n/* Begin PBXBuildFile section */\n')
for f in swift:
    w(f'\t\t{oid("bf"+f)} /* {os.path.basename(f)} in Sources */ = {{isa = PBXBuildFile; fileRef = {oid("fr"+f)}; }};\n')
for f in resources:
    w(f'\t\t{oid("bf"+f)} /* {os.path.basename(f)} in Resources */ = {{isa = PBXBuildFile; fileRef = {oid("fr"+f)}; }};\n')
for f in cpp:
    w(f'\t\t{oid("bf"+f)} /* {os.path.basename(f)} in Sources */ = {{isa = PBXBuildFile; fileRef = {oid("fr"+f)}; }};\n')
w('/* End PBXBuildFile section */\n')

w('\n/* Begin PBXFileReference section */\n')
w(f'\t\t{oid("product")} /* SoundField.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = SoundField.app; sourceTree = BUILT_PRODUCTS_DIR; }};\n')
for rel, (_, _, entries) in groups.items():
    for name, kind in entries:
        if kind == 'group': continue
        f = os.path.normpath(os.path.join(rel, name))
        w(f'\t\t{oid("fr"+f)} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = {ftype[kind]}; path = "{name}"; sourceTree = "<group>"; }};\n')
w('/* End PBXFileReference section */\n')

w('\n/* Begin PBXFrameworksBuildPhase section */\n')
w(f'\t\t{oid("fw")} /* Frameworks */ = {{isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; }};\n')
w('/* End PBXFrameworksBuildPhase section */\n')

w('\n/* Begin PBXGroup section */\n')
w(f'\t\t{oid("maingroup")} = {{isa = PBXGroup; children = ({oid("g..")} /* SoundField */, {oid("g.dsp")} /* dsp */, {oid("g.profiles") + " /* profiles */, " if "profiles" in groups else ""}{oid("products")} /* Products */, ); sourceTree = "<group>"; }};\n')
w(f'\t\t{oid("products")} /* Products */ = {{isa = PBXGroup; children = ({oid("product")} /* SoundField.app */, ); name = Products; sourceTree = "<group>"; }};\n')
for rel, (name, path_attr, entries) in groups.items():
    kids = []
    for kname, kind in entries:
        f = os.path.normpath(os.path.join(rel, kname)) if rel != 'dsp' else 'dsp/' + kname
        kids.append(f'{oid("g."+f if kind=="group" else "fr"+f)} /* {kname} */')
    w(f'\t\t{oid("g."+rel)} /* {name} */ = {{isa = PBXGroup; children = ({", ".join(kids)}, ); path = "{path_attr}"; sourceTree = "<group>"; }};\n')
w('/* End PBXGroup section */\n')

w('\n/* Begin PBXNativeTarget section */\n')
w(f'''\t\t{oid("target")} /* SoundField */ = {{
\t\t\tisa = PBXNativeTarget;
\t\t\tbuildConfigurationList = {oid("cl.target")};
\t\t\tbuildPhases = ({oid("sources")} /* Sources */, {oid("fw")} /* Frameworks */, {oid("resources")} /* Resources */, );
\t\t\tbuildRules = ();
\t\t\tdependencies = ();
\t\t\tname = SoundField;
\t\t\tproductName = SoundField;
\t\t\tproductReference = {oid("product")} /* SoundField.app */;
\t\t\tproductType = "com.apple.product-type.application";
\t\t}};
''')
w('/* End PBXNativeTarget section */\n')

w('\n/* Begin PBXProject section */\n')
w(f'''\t\t{oid("project")} /* Project object */ = {{
\t\t\tisa = PBXProject;
\t\t\tattributes = {{ BuildIndependentTargetsInParallel = 1; LastSwiftUpdateCheck = 1500; LastUpgradeCheck = 1500; TargetAttributes = {{ {oid("target")} = {{ CreatedOnToolsVersion = 15.0; }}; }}; }};
\t\t\tbuildConfigurationList = {oid("cl.project")};
\t\t\tcompatibilityVersion = "Xcode 14.0";
\t\t\tdevelopmentRegion = ru;
\t\t\thasScannedForEncodings = 0;
\t\t\tknownRegions = (en, ru, Base, );
\t\t\tmainGroup = {oid("maingroup")};
\t\t\tproductRefGroup = {oid("products")} /* Products */;
\t\t\tprojectDirPath = "";
\t\t\tprojectRoot = "";
\t\t\ttargets = ({oid("target")} /* SoundField */, );
\t\t}};
''')
w('/* End PBXProject section */\n')

w('\n/* Begin PBXResourcesBuildPhase section */\n')
w(f'\t\t{oid("resources")} /* Resources */ = {{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(oid("bf"+f)+" /* "+os.path.basename(f)+" in Resources */" for f in resources)}, ); runOnlyForDeploymentPostprocessing = 0; }};\n')
w('/* End PBXResourcesBuildPhase section */\n')
w('\n/* Begin PBXSourcesBuildPhase section */\n')
w(f'\t\t{oid("sources")} /* Sources */ = {{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(oid("bf"+f)+" /* "+os.path.basename(f)+" in Sources */" for f in swift + cpp)}, ); runOnlyForDeploymentPostprocessing = 0; }};\n')
w('/* End PBXSourcesBuildPhase section */\n')

common_proj = '''ALWAYS_SEARCH_USER_PATHS = NO; CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; ENABLE_STRICT_OBJC_MSGSEND = YES; GCC_NO_COMMON_BLOCKS = YES; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SDKROOT = iphoneos; SWIFT_VERSION = 5.0; SWIFT_STRICT_CONCURRENCY = minimal;'''
target_common = f'''ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor; CLANG_CXX_LANGUAGE_STANDARD = "c++20"; CLANG_CXX_LIBRARY = "libc++"; CODE_SIGN_STYLE = Automatic; CURRENT_PROJECT_VERSION = {CURRENT_PROJECT_VERSION}; DEVELOPMENT_TEAM = ""; ENABLE_PREVIEWS = YES; GENERATE_INFOPLIST_FILE = NO; HEADER_SEARCH_PATHS = "$(SRCROOT)/../dsp/include"; INFOPLIST_FILE = SoundField/Info.plist; LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks", ); MARKETING_VERSION = {MARKETING_VERSION}; OTHER_CPLUSPLUSFLAGS = "-ffp-contract=off"; PRODUCT_BUNDLE_IDENTIFIER = app.soundfield; PRODUCT_NAME = "$(TARGET_NAME)"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO; SWIFT_EMIT_LOC_STRINGS = YES; SWIFT_OBJC_BRIDGING_HEADER = SoundField/SoundField-Bridging-Header.h; TARGETED_DEVICE_FAMILY = 1;'''
w('\n/* Begin XCBuildConfiguration section */\n')
w(f'\t\t{oid("pc.debug")} /* Debug */ = {{isa = XCBuildConfiguration; buildSettings = {{ {common_proj} DEBUG_INFORMATION_FORMAT = dwarf; ENABLE_TESTABILITY = YES; GCC_OPTIMIZATION_LEVEL = 0; ONLY_ACTIVE_ARCH = YES; SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG; SWIFT_OPTIMIZATION_LEVEL = "-Onone"; }}; name = Debug; }};\n')
w(f'\t\t{oid("pc.release")} /* Release */ = {{isa = XCBuildConfiguration; buildSettings = {{ {common_proj} DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"; ENABLE_NS_ASSERTIONS = NO; SWIFT_COMPILATION_MODE = wholemodule; SWIFT_OPTIMIZATION_LEVEL = "-O"; VALIDATE_PRODUCT = YES; }}; name = Release; }};\n')
w(f'\t\t{oid("tc.debug")} /* Debug */ = {{isa = XCBuildConfiguration; buildSettings = {{ {target_common} }}; name = Debug; }};\n')
w(f'\t\t{oid("tc.release")} /* Release */ = {{isa = XCBuildConfiguration; buildSettings = {{ {target_common} }}; name = Release; }};\n')
w('/* End XCBuildConfiguration section */\n')

w('\n/* Begin XCConfigurationList section */\n')
w(f'\t\t{oid("cl.project")} /* Build configuration list for PBXProject "SoundField" */ = {{isa = XCConfigurationList; buildConfigurations = ({oid("pc.debug")} /* Debug */, {oid("pc.release")} /* Release */, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};\n')
w(f'\t\t{oid("cl.target")} /* Build configuration list for PBXNativeTarget "SoundField" */ = {{isa = XCConfigurationList; buildConfigurations = ({oid("tc.debug")} /* Debug */, {oid("tc.release")} /* Release */, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};\n')
w('/* End XCConfigurationList section */\n')
w(f'\t}};\n\trootObject = {oid("project")} /* Project object */;\n}}\n')

out = os.path.join(ROOT, 'SoundField.xcodeproj')
os.makedirs(out, exist_ok=True)
open(os.path.join(out, 'project.pbxproj'), 'w').write(''.join(L))
print('swift:', len(swift), 'cpp:', len(cpp), 'resources:', resources)
