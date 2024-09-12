

# Version 1.14.161
How to migrate Gradle project to start using Ember dependency BOM (bill of materials):

The change can be seen in [this diff](https://github.com/epam/ember-algo-sample/commit/bc477aa33695154694653012f1233f4060ede134), but we are going to explain this step-by-step:

1. Add the following section on top of your build.gradle:

```groovy
plugins {
    // Supports functionality similar to Maven BOM.
    // Helps to avoid re-declaring dependency version in each subproject.
    // See https://github.com/spring-gradle-plugins/dependency-management-plugin
    id "io.spring.dependency-management" version "1.1.5" apply false
}


apply plugin: 'java'
apply plugin: 'io.spring.dependency-management'
```

2. Right before your `dependencies{}` block add this section:

```groovy
dependencyManagement {
    imports {
        mavenBom 'deltix:deltix-ember-bom:' + emberVersion
    }
}
```

3. Simplify your `dependencies` block. Remove explicit version numbers from Ember and Ember's transitive dependencies:

```groovy
dependencies {
    implementation "deltix:deltix-anvil-lang"
    implementation "deltix:deltix-ember-api"
    implementation "deltix:deltix-ember-algo-api"
    implementation "deltix:deltix-ember-message"
    implementation "deltix:deltix-ember-codec"
    implementation "deltix:deltix-anvil-cluster"
    implementation "deltix:deltix-efix-core"

    // Centralized security master API
    implementation 'deltix:sm-messages'
    implementation 'deltix:deltix-sm-api'
}
```
