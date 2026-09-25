#!/usr/bin/env python3
"""Генерирует ios/SoundField.xcodeproj/project.pbxproj из файлов в ios/SoundField/.
Запуск: python3 tools/gen_xcodeproj.py (после добавления/удаления файлов)."""
import hashlib, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'ios')
SRC = os.path.join(ROOT, 'SoundField')

def oid(key):
    return hashlib.md5(key.encode()).hexdigest()[:24].upper()

groups = {}   # rel dir -> list of (name, kind)
swift, resources = [], []
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
    groups[os.path.normpath(rel)] = entries

ftype = {'swift': 'sourcecode.swift', 'assets': 'folder.assetcatalog', 'wav': 'audio.wav', 'plist': 'text.plist.xml'}
L = []
w = L.append
w('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 56;\n\tobjects = {\n')

w('\n/* Begin PBXBuildFile section */\n')
for f in swift:
    w(f'\t\t{oid("bf"+f)} /* {os.path.basename(f)} in Sources */ = {{isa = PBXBuildFile; fileRef = {oid("fr"+f)}; }};\n')
for f in resources:
    w(f'\t\t{oid("bf"+f)} /* {os.path.basename(f)} in Resources */ = {{isa = PBXBuildFile; fileRef = {oid("fr"+f)}; }};\n')
w('/* End PBXBuildFile section */\n')

w('\n/* Begin PBXFileReference section */\n')
w(f'\t\t{oid("product")} /* SoundField.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = SoundField.app; sourceTree = BUILT_PRODUCTS_DIR; }};\n')
for rel, entries in groups.items():
    for name, kind in entries:
        if kind == 'group': continue
        f = os.path.normpath(os.path.join(rel, name))
        w(f'\t\t{oid("fr"+f)} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = {ftype[kind]}; path = "{name}"; sourceTree = "<group>"; }};\n')
w('/* End PBXFileReference section */\n')

w('\n/* Begin PBXFrameworksBuildPhase section */\n')
w(f'\t\t{oid("fw")} /* Frameworks */ = {{isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; }};\n')
w('/* End PBXFrameworksBuildPhase section */\n')

w('\n/* Begin PBXGroup section */\n')
w(f'\t\t{oid("maingroup")} = {{isa = PBXGroup; children = ({oid("g..")} /* SoundField */, {oid("products")} /* Products */, ); sourceTree = "<group>"; }};\n')
w(f'\t\t{oid("products")} /* Products */ = {{isa = PBXGroup; children = ({oid("product")} /* SoundField.app */, ); name = Products; sourceTree = "<group>"; }};\n')
for rel, entries in groups.items():
    kids = []
    for name, kind in entries:
        f = os.path.normpath(os.path.join(rel, name))
        kids.append(f'{oid("g."+f if kind=="group" else "fr"+f)} /* {name} */')
    name = 'SoundField' if rel == '.' else os.path.basename(rel)
    w(f'\t\t{oid("g."+rel)} /* {name} */ = {{isa = PBXGroup; children = ({", ".join(kids)}, ); path = "{name}"; sourceTree = "<group>"; }};\n')
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
w(f'\t\t{oid("sources")} /* Sources */ = {{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(oid("bf"+f)+" /* "+os.path.basename(f)+" in Sources */" for f in swift)}, ); runOnlyForDeploymentPostprocessing = 0; }};\n')
w('/* End PBXSourcesBuildPhase section */\n')

common_proj = '''ALWAYS_SEARCH_USER_PATHS = NO; CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; ENABLE_STRICT_OBJC_MSGSEND = YES; GCC_NO_COMMON_BLOCKS = YES; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SDKROOT = iphoneos; SWIFT_VERSION = 5.0; SWIFT_STRICT_CONCURRENCY = minimal;'''
target_common = '''ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor; CODE_SIGN_STYLE = Automatic; CURRENT_PROJECT_VERSION = 1; DEVELOPMENT_TEAM = ""; ENABLE_PREVIEWS = YES; GENERATE_INFOPLIST_FILE = NO; INFOPLIST_FILE = SoundField/Info.plist; LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks", ); MARKETING_VERSION = 1.0.0; PRODUCT_BUNDLE_IDENTIFIER = app.soundfield; PRODUCT_NAME = "$(TARGET_NAME)"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO; SWIFT_EMIT_LOC_STRINGS = YES; TARGETED_DEVICE_FAMILY = 1;'''
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
print('swift:', len(swift), 'resources:', resources)
