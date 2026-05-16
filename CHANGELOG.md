# Changelog

All notable changes to this project will be documented in this file (created with git cliff).

## [0.3.2] - 2026-05-16

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/5e026d9c540bd26cf3fbe2ba2775b06ff39c3cd8..ff7b1fb3288b17986753553a6dd56b0713619ec7)
### ⚙️ Miscellaneous Tasks


- Update gradle version to 9.5.1 ([560deb2](https://github.com/amasotti/doppler-intellj-plugin/commit/560deb20112bbc9f099bc05d52ba6f6edc281320))

- Add no installer flag for Rider (test config) ([c4eadb5](https://github.com/amasotti/doppler-intellj-plugin/commit/c4eadb5f83cde4fb858f6c4ce567f1a3f1c1b0b3))

### Security


- Add lockfile for gradle dependencies ([661aee1](https://github.com/amasotti/doppler-intellj-plugin/commit/661aee16b5ba617c7efe31ac9edf67ca704b21c4))

- Create lockfile for dependencies ([ff7b1fb](https://github.com/amasotti/doppler-intellj-plugin/commit/ff7b1fb3288b17986753553a6dd56b0713619ec7))

## [0.3.1] - 2026-05-11

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/6c3a6a2c5267aa6eb662ea41b81c54b3ee03ba43..5e026d9c540bd26cf3fbe2ba2775b06ff39c3cd8)
### ⚙️ Miscellaneous Tasks


- Integrate changelog plugin and update versioning details ([5e026d9](https://github.com/amasotti/doppler-intellj-plugin/commit/5e026d9c540bd26cf3fbe2ba2775b06ff39c3cd8))

## [0.3.0] - 2026-05-11

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/51c53e3ad0a051c71b015db22e3784185f2c3eee..6c3a6a2c5267aa6eb662ea41b81c54b3ee03ba43)
### 🚀 Features


- Add search functionality to secrets table ([1ec34af](https://github.com/amasotti/doppler-intellj-plugin/commit/1ec34af2152c2d8b072712519172c99115bccbce))

### 🐛 Bug Fixes


- Disable PythonCore in non-Python IDE sandboxes to prevent TOML dependency balloon ([d81a55a](https://github.com/amasotti/doppler-intellj-plugin/commit/d81a55a26a5d003fa7aab18a13ca63442cdf8cd4))

- Update settings dialog invocation to use DopplerSettingsConfigurable class ([9b4e428](https://github.com/amasotti/doppler-intellj-plugin/commit/9b4e42885c71d9817751fec53823206f6e5451f9))

### ⚙️ Miscellaneous Tasks


- Enhance CLI path detection and UI feedback in settings ([6c3a6a2](https://github.com/amasotti/doppler-intellj-plugin/commit/6c3a6a2c5267aa6eb662ea41b81c54b3ee03ba43))

## [0.2.1] - 2026-05-10

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/3dc22cd1fe1066d7f1d2721bdd8554db96815531..51c53e3ad0a051c71b015db22e3784185f2c3eee)
### 🚀 Features


- Register new gradle extension for newer gradle versions ([6ef5bf1](https://github.com/amasotti/doppler-intellj-plugin/commit/6ef5bf141f2ce3c1c9e75a36db8acd0cbdca94be))

### 🐛 Bug Fixes


- Tags with / without v prefix ([dfd87d8](https://github.com/amasotti/doppler-intellj-plugin/commit/dfd87d8bd99b8fbe0e830ee169447244eea47bf3))

- Improve file chooser dialog title for CLI binary selection ([4fd7d7b](https://github.com/amasotti/doppler-intellj-plugin/commit/4fd7d7bf2ce0584c59160ebd73736ffaf7d3d621))

- Pin verifier to IU-2026.1 to avoid compatibility errors with PythonCore ([3a19dda](https://github.com/amasotti/doppler-intellj-plugin/commit/3a19dda75b45f2c8c37018ccebb6d9c154f02f1d))

- Pin PythonCore and add debug logging expressions ([8860193](https://github.com/amasotti/doppler-intellj-plugin/commit/886019378b5ebaaf81160aac5f354d39c25bda35))

- Race condition in UI in setting doppler project and config ([b507536](https://github.com/amasotti/doppler-intellj-plugin/commit/b507536bc70bec7994c35e907808da8be9d8715c))

- Prevent race condition in project loading during component creation ([dbf4bd7](https://github.com/amasotti/doppler-intellj-plugin/commit/dbf4bd73de4de45d03a7ebd4f817bb261527f329))

### ⚙️ Miscellaneous Tasks


- Exclude log files from git ([a1478f5](https://github.com/amasotti/doppler-intellj-plugin/commit/a1478f5e2825e216f09be1a515a5e24a068272f4))

- Increase MaxLineLength ([7e16421](https://github.com/amasotti/doppler-intellj-plugin/commit/7e16421bfe13b1be78ee85879189f39b789ba25d))

## [0.2.0] - 2026-05-10

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/1543b9b3346bd9cb7e46fd165888251a8a1b176b..3dc22cd1fe1066d7f1d2721bdd8554db96815531)
### 🚀 Features


- Add support for Node.js and Python run configurations with secret injection ([a308bdf](https://github.com/amasotti/doppler-intellj-plugin/commit/a308bdfffedf35dd8dad081dcf0a18e382b95bca))

### 🐛 Bug Fixes


- Update Python plugin dependency and adjust test framework versions for IntelliJ 2026.1 compatibility ([0292239](https://github.com/amasotti/doppler-intellj-plugin/commit/0292239d983b784d5033cc52265582df942924f2))

- Prevent injection when project is disposed ([80a4558](https://github.com/amasotti/doppler-intellj-plugin/commit/80a45586ffddc4de2153dad0f024bfbf9c1f2f07))

### ⚙️ Miscellaneous Tasks


- Update versioning strategy and enhance CI configuration ([3dc22cd](https://github.com/amasotti/doppler-intellj-plugin/commit/3dc22cd1fe1066d7f1d2721bdd8554db96815531))

## [0.1.2] - 2026-05-10

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/10b1fa789efdee6cbc3a684f85acdb2d4f88d093..1543b9b3346bd9cb7e46fd165888251a8a1b176b)
### 🚀 Features


- Improve error handling and user feedback during secrets fetching ([16ab833](https://github.com/amasotti/doppler-intellj-plugin/commit/16ab8332e6df7aa6f3bcdf8eab778915e5de8656))

- Add tool icon ([d4c84c6](https://github.com/amasotti/doppler-intellj-plugin/commit/d4c84c66e31a42fa4a3d31caead11e195d9c119e))

- Update version to 0.1.2 and add cross-IDE smoke testing configurations ([1543b9b](https://github.com/amasotti/doppler-intellj-plugin/commit/1543b9b3346bd9cb7e46fd165888251a8a1b176b))

### 🚜 Refactor


- Use ActionButtons instead of plain Jbuttons ([2d4d570](https://github.com/amasotti/doppler-intellj-plugin/commit/2d4d570ee7b49d8dd07a048ee39e359f037f5c0f))

### 🧪 Testing


- Add test for hook buttons ([a4ab235](https://github.com/amasotti/doppler-intellj-plugin/commit/a4ab23511f3ec60f585dd867668fb8ce580e5a23))

## [0.1.1] - 2026-05-09

[Compare with last version](https://github.com/amasotti/doppler-intellj-plugin/compare/924f3878fcee37d5372bea91957625d6ee2bc6fa..10b1fa789efdee6cbc3a684f85acdb2d4f88d093)
### 🚀 Features


- Add toolWindow ([6948acc](https://github.com/amasotti/doppler-intellj-plugin/commit/6948acc0ac793388c5c971573a861bc65466e655))

### 🐛 Bug Fixes


- Add TestOnly constructor to DopplerProjectService for better testability ([2753667](https://github.com/amasotti/doppler-intellj-plugin/commit/275366776d2a0bd19d040fc884770353607c56bb))

## [0.1.0] - 2026-05-09

### 🚀 Features


- Init repo ([77e2fb5](https://github.com/amasotti/doppler-intellj-plugin/commit/77e2fb5d31f6f3543fc07f10582737260a80ebca))

- Bootstrap project ([cd181c9](https://github.com/amasotti/doppler-intellj-plugin/commit/cd181c9ab46f2044ca556d29e484d238e0d1767b))

- Implement doppler CLI layer ([efd9683](https://github.com/amasotti/doppler-intellj-plugin/commit/efd96830433b569d5095e87a7e0919d77a3fecab))

- Local concurrent cache layer ([d8fe9ac](https://github.com/amasotti/doppler-intellj-plugin/commit/d8fe9acbea36820863b88b8b21439fd57a5a0a18))

- Doppler setting state in .idea folder ([dec7743](https://github.com/amasotti/doppler-intellj-plugin/commit/dec7743c642768939b6c045d6e1c6c95f5bb4ba5))

- Add simple notifier layer ([79d0924](https://github.com/amasotti/doppler-intellj-plugin/commit/79d09241386601d414fc21920b9c04d91691824d))

- Enhance local cache to accept ttl ([cf014ec](https://github.com/amasotti/doppler-intellj-plugin/commit/cf014ece9f95197e26fb7d4c754739103705e502))

- Add JUnit5 as test framework for IntelliJ platform ([801a133](https://github.com/amasotti/doppler-intellj-plugin/commit/801a1336979e2a63fa7aef22f228c2f9d970f0ec))

- Implement DopplerProjectService for managing secrets in IDE projects ([f2aab34](https://github.com/amasotti/doppler-intellj-plugin/commit/f2aab345bcc4c04525bb4dc43d194a2e5abc9bd4))

- DopplerFetchException for CLI failure and improve projectService ([e386093](https://github.com/amasotti/doppler-intellj-plugin/commit/e3860932775d633cce67a06c1acad77a1a07031f))

- Secret merger and core injection (platform-agnostic) ([330de53](https://github.com/amasotti/doppler-intellj-plugin/commit/330de535282c83396bc7ac34ec18d7e96e4f1f5d))

- Add Gradle run configuration extension for Doppler secret injection ([39ab97c](https://github.com/amasotti/doppler-intellj-plugin/commit/39ab97c2a7766ebc801580e8a88076ba6c603134))

- Enhance injectSecrets method with overridable notification callbacks for errors and warnings ([06dc9bf](https://github.com/amasotti/doppler-intellj-plugin/commit/06dc9bf7d26bd798846e8f1599d8d4352d5897eb))

- Implmenet jvm family injector (java / kotlin / spring etc.) ([6a7b717](https://github.com/amasotti/doppler-intellj-plugin/commit/6a7b717e5ce368ef1c295a1df39888a443c5151c))

- Restrict applicability of DopplerJavaRunConfigurationExtension to JVM-family configurations ([c7cf9c1](https://github.com/amasotti/doppler-intellj-plugin/commit/c7cf9c17d648d372f6d7bb377621e720b7a5dab4))

- Implement settings UI (Phase 9) ([c19f514](https://github.com/amasotti/doppler-intellj-plugin/commit/c19f5146fd41bb144174f533dc9b95715bb86819))

- Enhance logging and error handling in DopplerSettingsPanel ([9d5260d](https://github.com/amasotti/doppler-intellj-plugin/commit/9d5260da08215f7c478f712041893b7f66df3cd5))

### 🐛 Bug Fixes


- Clarify comment on CLI failure handling in DopplerProjectService ([6a0e507](https://github.com/amasotti/doppler-intellj-plugin/commit/6a0e507715fb5b67005a0cfb30658f67e5e1ccb8))

- Improve stream handling on command timeout in DopplerCliClient ([b86f116](https://github.com/amasotti/doppler-intellj-plugin/commit/b86f11676e86f93a5e0cd01517ed2e3b9dbf3e65))

- Improve process stream handling and ensure proper resource cleanup in DopplerCliClient ([c2be01d](https://github.com/amasotti/doppler-intellj-plugin/commit/c2be01da7f153bd4dfe3f1603d108539b8212759))

- Implement killProcessTree to ensure proper cleanup of process descendants in DopplerCliClient ([febb7dd](https://github.com/amasotti/doppler-intellj-plugin/commit/febb7ddd96e3e9e68fc6160755b7c902951ad5d0))

- Add logging for stream pump termination in DopplerCliClient ([f7fc233](https://github.com/amasotti/doppler-intellj-plugin/commit/f7fc233711b8af0132e94b32ac18b6a4bd1701d0))

- Fix IntelliJ test runtime mismatch in DopplerProjectService tests ([8a8b997](https://github.com/amasotti/doppler-intellj-plugin/commit/8a8b9971717e2e3a5f30a11e92d9b55f7b471e4a))

- Pin JUnit Jupiter version for compatibility with IntelliJ tests ([75a9897](https://github.com/amasotti/doppler-intellj-plugin/commit/75a9897913b6af2162045a3953eacc557a2fd877))

- Improve slug assignment in DopplerProject initialization ([924f387](https://github.com/amasotti/doppler-intellj-plugin/commit/924f3878fcee37d5372bea91957625d6ee2bc6fa))

### 🧪 Testing


- Adapt test to let cache store accept ttl ([79f6f5a](https://github.com/amasotti/doppler-intellj-plugin/commit/79f6f5acce66d697e10a108812c61a1bcada77b9))

- TDD approach - write projectService test first ([f561c60](https://github.com/amasotti/doppler-intellj-plugin/commit/f561c60831f8f0fd41689271d26eab9562dd5fd1))

- Improve error handling in fetchSecrets ([c9c00e3](https://github.com/amasotti/doppler-intellj-plugin/commit/c9c00e3ca0f9eee592fb6b3af0a9a6f3fbe40ce5))

- Add test for redactedView entries and values leakage in DopplerProjectService ([a17ad59](https://github.com/amasotti/doppler-intellj-plugin/commit/a17ad59c6bc79cac38a2d086a4578f2e87c01c36))

- Rename overriddenKeys to shadowedKeys in SecretMergerTest for clarity ([f720e22](https://github.com/amasotti/doppler-intellj-plugin/commit/f720e22d902ba843f6efc0c184b205c8180d19da))

### ⚙️ Miscellaneous Tasks


- Add license MIT ([a9c0860](https://github.com/amasotti/doppler-intellj-plugin/commit/a9c086076bfae97bdd626a41180ae6b97277a825))

- Update gradle (v9.5) ([9412fd8](https://github.com/amasotti/doppler-intellj-plugin/commit/9412fd891c9f0d89292a876c5ea4639855049141))

- Fix group naming ([e6812f4](https://github.com/amasotti/doppler-intellj-plugin/commit/e6812f491fcd40a11ad3d47a6be2b626695fa2ef))

- Complete build.gradle.kts general setup ([ca35309](https://github.com/amasotti/doppler-intellj-plugin/commit/ca3530979aadada972b6413450d124f9045428c5))

- Detekt configuration ([cfc7c8f](https://github.com/amasotti/doppler-intellj-plugin/commit/cfc7c8f589b46ec98121057109e3693b0a50b123))

- Add github tooling ([d75eb64](https://github.com/amasotti/doppler-intellj-plugin/commit/d75eb64e2386d47068926918cffa4faefd0cfddc))

- Github actions ([44470bb](https://github.com/amasotti/doppler-intellj-plugin/commit/44470bbd8048720d89b8ed8f8aa8e10b774af260))

- Remove caching (pay to use) ([e539414](https://github.com/amasotti/doppler-intellj-plugin/commit/e53941465b0e39b1f3b1251cf16bc0b744f959d1))

- Improve logo ([c699e31](https://github.com/amasotti/doppler-intellj-plugin/commit/c699e31697ad88cd47cd5fe57dcc14e485599027))

- Update kotlin version in kotlinc.xml file ([f577229](https://github.com/amasotti/doppler-intellj-plugin/commit/f5772292acbb7b27c4eaee5b8e7d773895bee493))

- Stop tracking machine-specific .idea/ files ([2500bcd](https://github.com/amasotti/doppler-intellj-plugin/commit/2500bcdeefc546ac2b247c4c53e10afe2244874b))

- Update JDK version from 25 to 21 in build configurations ([86f31c9](https://github.com/amasotti/doppler-intellj-plugin/commit/86f31c911543d276227ffc2802f35e60d56cd101))

### Security


- Make sure to trim local paths when storing project config ([4fcad56](https://github.com/amasotti/doppler-intellj-plugin/commit/4fcad567ab05463bc30f829c616277419f207156))

<!-- generated by git-cliff -->
