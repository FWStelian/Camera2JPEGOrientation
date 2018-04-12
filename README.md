# FW Android Architecture

<img src="http://www.futureworkshops.com/img/logo.jpg" alt="Futureworkshops Android Architecture"/>

This repository aimes to provide a starting point when deciding the architecture and company-related practices when starting a new project.  

No project is ever going to have the same requirements so this is not to be viewed as a rule book!   

The guidelines described here have the purpose of maintaining a common denominator for all the projects that we develop at FW. The advantages are pretty obvius if you think at the business model of the company:  
similar project structure means onboarding new developers is a lot easier, this also applies to project hand-offs, code reviews,etc.

## TODO
- [x] wiki for SchedulerProvider - **Vlad**
- [x] wiki for Dagger usage  - **Dimitrios**
- [ ] section for testing + wiki
- [ ] section about code style
- [ ] useful plugins
- [ ] code style plugins
- [ ] .gitingore coverage
- [ ] Navigator section + Wiki - **Jose**

## Code Organisation

Describes how code should be organised inside the project. Topics include organising code into packages, managing dependencies, CI integration and testing approach.

### Packages

#### model
 Holds all POJOs used in the application. These models should not, ideally, have any framework specific additions (ex: extending RealmObject).
  Can organize into sub-packages(if necessary) as you see fit.

#### domain
This package will contain all code related to data production as far as the app is concerned. This can include network requests, database operations,
 dependency injection and rx Java classes that are not specific to a certain feature.

  **network**  
  This package holds all the code related to networking (typically the REST api and Retrofit manager). All code that maps specific netowrk responses to the application *models* should also go here. Sub-packages are allowed if necesary.

 **persistence**  
 This package will hold all the code related to storing information to a database or SharedPreference. All persistence related maping code will also go here.

 **dagger**  
This package will contain code related to dependency injection that is used throughout the application (modules or components that provide data stores or repositories). Custom scopes and annotations should also be defined here.

 **rx**  
RxJava specific code goes here. Examples are `SchedulerProvider` (used for specifying the schedulers that a specific call should use). Check the Wiki to see how to use it.  


#### presentation  
This package contains all the code related to the app features and UI. Sub-packages will be organised by feature not by component type.
Sub-packages can be very simple(like the login sub-package) or very complex (TODO insert example here).
You should aim to have all feature-related code in a single package, except if the feature uses some common components.

**common**  
 This package holds components used by more than 1 features of the app. Example of common components can include :  
* BaseActivity -> a class that can handle Toolbar initialization  
* a custom view  
* a custom RecyclerViewDecorator    

**feature-packages**  
This is the feature package. All components that are specific to this feature will go in here. 
A typical feature package will contain :  
- `dagger` - package that contains Dagger module and component for this feature (see Wiki for usage)  
- `FeatureContract` - class that contains definitions for the View and Presenter methods  
- `FeaturePresenter`  - class that maps user interaction with domain operations exposed in the interactor  
- `FeatureInteractor`  - class that is responsible for performing domain operations(getting data from repositories,saving,etc.)  
- `FeatureView` or `views` - if we have a simple screen we might only need the View component; if we have a more complex screen(like a screen that displays a RecyclerView) we add a sub-package that will contain all components related to UI (ex: RecyclerViewAdapter, custom items that will be displayed in the RecyclerView, custom ItemDecorator used only in this feature,etc. ).  
In order to properly unit-test the Presenters we need to add an extra level of abstraction - the interactor.  
The interactor will have all methods required for the feature. Using this approach we might have a small amount of duplicate code(some feature might use the same model so we duplicate) but it gives more freedom in customizing the repository output to match the feature requirement - the alternative involves interactors for every operation(instead of every feature); this can remove some duplicate code but it becomes cumbersome if every feature needs a slightly different data set.

**utils**  
All the Util classes go in here.

## Gradle  

When it comes to Gradle organisation we use 3 separate files to manage *dependencies*,*versioning*  and *CI configuration*.  
In this serction we will discuss dependencies and component versioning.  

**android_commons.gradle**  
This file will contain common values for app wide components like *minSdk*,*targetSDK*, *gradle-tools version*,*repositories*,etc.  

To apply the values defined in this file(located at root of the project) you need to add ```apply from: '../android_commons.gradle'``` to your `build.gradle` file.  

**android_dependencies.gradle**  
This file defines all the dependencies used in all the modules of the project. The purpose is to manage dependencies in one place and make sure that all modules use the same dependency versions.  

Dependencies are structured in 2 categories `domain`, `ui` and `test`. Domain dependencies contain network, dagger, database dependencies,etc. UI contains dependencies to UI libraries like appCompat,butterknife,etc. and test will contain dependencies related to testing only.

To use a dependency defined in this file you need to add the following to your `dependencies` block:  


    def ui = rootProject.ext.uiDependencies
    def domain = rootProject.ext.domainDependencies
    def test = rootProject.ext.testDependecies

    compile ui.appCompat
    compile retrofit
    testCompile test.junit
    
## CI  
The CI will handle minor versining, app signing and publishing of the apk.  
In order to enable CI integration you need to define a `gradle.properties`  file with the following properties at the root of your project:  

- project.buildversion  
- project.buildnumber   

All properties except **project.buildversion** will be injected by the CI and don't need to be updated manually. **buildversion** needs to be incremented manually!  

To complete the integration, your `build.gradle` file needs to use the values defined in this file:  

    android {
    	defaultConfig {
        	versionCode Integer.parseInt(project.property('project.buildnumber'))
        	versionName project.property('project.buildversion')    
    	}
   }    


## Testing

## Code style  

### First time setup

The general FW code style settings can be downloaded from [this jar](files/``fw_codestyle.jar) and imported like this:
`AndroidStudio -> File -> Import Settings...` 

This approach is recommended if you just joined FW and need to configure your Android Studio instalation.

### Project specific code style 
Because projects can have different code style requirements we sync these settings using Github.

Each project can contain files that define a specific code style, a custom copyright notice,etc. -> these files are placed inside the **.idea** folder. 
Based on [InteliJ Documentation](https://www.jetbrains.com/help/idea/2016.1/synchronizing-and-sharing-settings.html) the files shown in the snippet below are safe to commit to version control.

We do that by configuring the root *.gitignore* file to include the files we need and exclude everything else from the *.idea* folder. 

<img src="art/gitignore.png" alt="gitignore"/>

You can see the actual file [here](.gitignore).

### Checkstyle settings
You can add `checkstyle` rules to your project by copying the **config/checkstyle** folder to your project and then modify the root `build.gradle` to include:

    subprojects {
        apply from: new File(rootDir, "config/checkstyle/checkstyle.gradle")
        afterEvaluate {
            tasks.findByName('check').dependsOn('checkstyle')
        }
    }

The rules are defined in [checkstyle.xml](config/checkstyle/checkstyle.xml) and [supressions.xml](config/checkstyle/supressions.xml) and they can be easily configured to match the project requirements.

To run checkstyle you can open a terminal and run `gradlew checkstyle`.

## GIT
We use an internal instance of Gitlab and a customized `git-flow`.  

### Branching model
In normal circumstances we use 4 permanent branches supplimented by *feature*, *bug-fix* and *hot-fix* branches:  
- master  
- dev  
- release  
- release_next  

**master**  
Master branch will be as clean as possible and will only contain code that has been released to the client.  

**dev**  
This is the development branch, all feature branches and bug fix branches will be merged into the `dev` branch, ALWAYS with a commit message.

**release**  
This branch contains the current release until it has passed acceptance from the internal QA and also the client. `Hot-fix` branches will be generated from this branch and merged back to `dev` when work is completed. After the branch has passed QA it will be merged to `master` and a `TAG` will be created.  

**release-next**  
This is a "special" branch not specified by the regular git-flow. It's purpose is to demo current state of the app or incomplete features to the client. This branch should NOT be merged in any other branches! All it's code comes from either `dev` or a `feature branch`.  

### Commits  
Commits should be as **atomical** as possible - you should not commit more than 1 file unless the app will not compile otherwise. Ofcourse, exceptions will apply.  

**Commit message structure**  
We generaly use `Pivotal` to track stories(features/bugs/chores) so each story will have a unique identifier.  
A commit message should folow this structure :

`<task_type>: #<story_id>, <commit message> `  

where:  
- `task_type` is feature/bug_fix/chore  
- `story_id` is the unique id of the Pivotal story

Example :  
```feature: #134582695, check that page is !=null before trying to delete it ```  

Try to avoid extra long commit messages.
