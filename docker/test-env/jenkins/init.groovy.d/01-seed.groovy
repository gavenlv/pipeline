// init.groovy - runs at Jenkins startup.
// Configures Jenkins WITHOUT JCasC (which was removed due to plugin
// version incompatibility with Jenkins 2.541.2).
//
// Responsibilities:
//   1) Set up security realm (local admin user).
//   2) Set up authorization strategy.
//   3) Register the global pipeline library.
//   4) Set up credentials (nexus-deployer, docker-registry).
//   5) Seed the "apex-modules-test" pipeline job.
//   6) Trigger the first build after Jenkins finishes booting.

import hudson.model.*
import hudson.security.*
import jenkins.model.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*
import org.jenkinsci.plugins.plaincredentials.impl.*
import hudson.util.Secret

def inst = Jenkins.instance
println '[apex-init] Boot hook starting...'

// Allow local file:// Git checkouts for shared library
System.setProperty('hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT', 'true')
try {
    def gitPlugin = inst.pluginManager.getPlugin('git')
    if (gitPlugin != null) {
        def gitScmClass = gitPlugin.classLoader.loadClass('hudson.plugins.git.GitSCM')
        def field = gitScmClass.getDeclaredField('ALLOW_LOCAL_CHECKOUT')
        field.setAccessible(true)
        field.setBoolean(null, true)
        println '[apex-init] Set GitSCM.ALLOW_LOCAL_CHECKOUT=true'
    }
} catch (Exception e) {
    println '[apex-init] Failed to set ALLOW_LOCAL_CHECKOUT: ' + e.message
}

// 0) Sanity check: required workflow classes must be present
try {
    Class.forName('org.jenkinsci.plugins.workflow.job.WorkflowJob')
    Class.forName('org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition')
    println '[apex-init] Workflow plugin classes resolved'
} catch (Throwable t) {
    println '[apex-init] WARNING: required workflow plugin classes not available: ' + t.message
    println '[apex-init] Skipping job seed; will retry on next boot'
    return
}

// 1) Security realm - local admin user
def securityRealm = new HudsonPrivateSecurityRealm(false)
try {
    securityRealm.createAccount("admin", "admin123")
    println '[apex-init] Created/updated admin user'
} catch (Exception e) {
    println '[apex-init] Admin user already exists: ' + e.message
}
inst.setSecurityRealm(securityRealm)

// 2) Authorization strategy - logged-in users can do anything
inst.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy())
println '[apex-init] Security configured'

// 3) Global pipeline library
try {
    def libConf = inst.getDescriptor('org.jenkinsci.plugins.workflow.libs.GlobalLibraries')
    if (libConf != null) {
        def libs = libConf.getLibraries()
        def existing = libs.find { it.name == 'apex-ci-library-local' }
        if (existing != null) {
            // Update existing library to allow version override
            def groovyLibPlugin = inst.pluginManager.getPlugin('pipeline-groovy-lib')
            def lcClass = groovyLibPlugin.classLoader.loadClass('org.jenkinsci.plugins.workflow.libs.LibraryConfiguration')
            lcClass.getMethod('setAllowVersionOverride', boolean.class).invoke(existing, true)
            libConf.save()
            println '[apex-init] Updated existing library apex-ci-library-local (allowVersionOverride=true)'
        } else {
            def groovyLibPlugin = inst.pluginManager.getPlugin('pipeline-groovy-lib')
            def gitPlugin = inst.pluginManager.getPlugin('git')
            if (groovyLibPlugin != null && gitPlugin != null) {
                def groovyCl = groovyLibPlugin.classLoader
                def gitCl = gitPlugin.classLoader

                // GitSCM(String url) - simplest constructor
                def gitScmClass = gitCl.loadClass('hudson.plugins.git.GitSCM')
                def scm = gitScmClass.getDeclaredConstructor(String.class).newInstance('file:///var/jenkins_home/casc_libs/apex-ci-library')

                // SCMRetriever(SCM scm)
                def scmRetrieverClass = groovyCl.loadClass('org.jenkinsci.plugins.workflow.libs.SCMRetriever')
                def retriever = scmRetrieverClass.getDeclaredConstructor(gitCl.loadClass('hudson.scm.SCM')).newInstance(scm)

                // LibraryConfiguration(String name, LibraryRetriever retriever)
                def lcClass = groovyCl.loadClass('org.jenkinsci.plugins.workflow.libs.LibraryConfiguration')
                def lib = lcClass.getDeclaredConstructor(String.class, groovyCl.loadClass('org.jenkinsci.plugins.workflow.libs.LibraryRetriever')).newInstance('apex-ci-library-local', retriever)
                lcClass.getMethod('setDefaultVersion', String.class).invoke(lib, 'main')
                lcClass.getMethod('setImplicit', boolean.class).invoke(lib, true)
                lcClass.getMethod('setAllowVersionOverride', boolean.class).invoke(lib, true)
                libs.add(lib)
                libConf.save()
                println '[apex-init] Registered global library apex-ci-library-local (SCMRetriever + GitSCM)'
            } else {
                println '[apex-init] WARNING: pipeline-groovy-lib or git plugin not found'
            }
        }
    } else {
        println '[apex-init] WARNING: GlobalLibraries descriptor not found'
    }
} catch (Exception e) {
    println '[apex-init] Library registration failed: ' + e.message
    e.printStackTrace()
}

// 4) Credentials
try {
    def credsStore = Jenkins.instance.getExtensionList(
        'com.cloudbees.plugins.credentials.SystemCredentialsProvider'
    )[0]
    def domain = Domain.global()
    def existing = credsStore.getCredentials(domain)

    // nexus-deployer
    def hasNexus = existing.find { it.id == 'nexus-deployer' }
    if (hasNexus == null) {
        def nexusCred = new UsernamePasswordCredentialsImpl(
            CredentialsScope.GLOBAL,
            'nexus-deployer',
            'Nexus deployment user',
            'deployment',
            'deployment123'
        )
        credsStore.addCredentials(domain, nexusCred)
        println '[apex-init] Added nexus-deployer credential'
    }

    // docker-registry-creds
    def hasDocker = existing.find { it.id == 'docker-registry-creds' }
    if (hasDocker == null) {
        def dockerCred = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            'docker-registry-creds',
            'Local registry creds (user:pass)',
            Secret.fromString('deployment:deployment123')
        )
        credsStore.addCredentials(domain, dockerCred)
        println '[apex-init] Added docker-registry-creds credential'
    }

    credsStore.save()
} catch (Exception e) {
    println '[apex-init] Credential setup failed: ' + e.message
}

// 5) Seed the module-test pipeline job
def jobName = 'apex-modules-test'
try {
    def wfPlugin = inst.pluginManager.getPlugin('workflow-job')
    def cpsPlugin = inst.pluginManager.getPlugin('workflow-cps')
    if (wfPlugin == null || cpsPlugin == null) {
        println "[apex-init] WARNING: workflow-job or workflow-cps plugin not found, skipping job seed"
    } else {
        def wfCl = wfPlugin.classLoader
        def cpsCl = cpsPlugin.classLoader
        def wfJobClass = wfCl.loadClass('org.jenkinsci.plugins.workflow.job.WorkflowJob')
        def cpsClass = cpsCl.loadClass('org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition')

        def job = inst.getItem(jobName)
        if (job == null || !wfJobClass.isInstance(job)) {
            job = inst.createProject(wfJobClass, jobName)
            println "[apex-init] Created job ${jobName}"
        } else {
            println "[apex-init] Job ${jobName} already exists, reusing"
        }

        def jenkinsfile = new File('/var/jenkins_home/Jenkinsfile-modules')
        if (jenkinsfile.exists()) {
            // CpsFlowDefinition(String script, boolean sandbox)
            def defn = cpsClass.getDeclaredConstructor(String.class, boolean.class).newInstance(jenkinsfile.text, false)
            job.setDefinition(defn)
            job.setConcurrentBuild(true)
            job.description = 'Exercises all public module interfaces of apex-ci-library'
            println "[apex-init] Job ${jobName} definition updated (${jenkinsfile.length()} bytes)"

            // Approve the script to bypass script-security
            try {
                def ssPlugin = inst.pluginManager.getPlugin('script-security')
                if (ssPlugin != null) {
                    def ssCl = ssPlugin.classLoader
                    def scriptApprovalClass = ssCl.loadClass('org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval')
                    def approval = scriptApprovalClass.getMethod('get').invoke(null)
                    def groovyLangClass = ssCl.loadClass('org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage')
                    def lang = groovyLangClass.getMethod('get').invoke(null)
                    // preapprove(String script, Language language) returns hash and adds to approved list
                    def hash = scriptApprovalClass.getMethod('preapprove', String.class, ssCl.loadClass('org.jenkinsci.plugins.scriptsecurity.scripts.Language')).invoke(approval, jenkinsfile.text, lang)
                    approval.save()
                    println "[apex-init] Script approved for ${jobName} (hash: ${hash})"
                }
            } catch (Exception se) {
                println '[apex-init] Script approval failed: ' + se.message
            }
        } else {
            println "[apex-init] WARNING: ${jenkinsfile} not found"
        }
    }
} catch (Exception e) {
    println '[apex-init] Job seed failed: ' + e.message
    e.printStackTrace()
}

inst.save()

// 6) Trigger the first build asynchronously so we don't block Jenkins boot
new Timer().schedule(new TimerTask() {
    @Override
    void run() {
        try {
            def j = Jenkins.instance.getItem(jobName)
            if (j != null) {
                println '[apex-init] Triggering initial build of ' + jobName
                j.scheduleBuild(new hudson.model.Cause.UserIdCause())
            }
        } catch (Throwable t) {
            println '[apex-init] Initial build trigger failed: ' + t
        }
    }
}, 30000L)

println '[apex-init] Boot hook complete'
