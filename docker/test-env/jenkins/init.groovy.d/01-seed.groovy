// init.groovy - runs at Jenkins startup AFTER JCasC has been applied.
// Responsibilities:
//   1) Wait for plugin manager to settle.
//   2) Seed the "apex-modules-test" pipeline job pointing at the
//      host-mounted Jenkinsfile-modules.
//   3) Trigger the first build after Jenkins finishes booting.
//
// Plugins are pre-staged by apex-entrypoint.sh, so by the time this
// script runs the workflow plugins are already on the classpath.

import hudson.model.*
import jenkins.model.*
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def inst = Jenkins.instance
println '[apex-init] Boot hook starting...'

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

// 1) Seed the module-test pipeline job
def jobName = 'apex-modules-test'
def job = inst.getItem(jobName) as WorkflowJob
if (job == null) {
    job = inst.createProject(WorkflowJob, jobName)
    println "[apex-init] Created job ${jobName}"
} else {
    println "[apex-init] Job ${jobName} already exists, reusing"
}

def jenkinsfile = new File('/var/jenkins_home/Jenkinsfile-modules')
if (jenkinsfile.exists()) {
    def defn = new CpsFlowDefinition(jenkinsfile.text, 'apex-modules-test')
    defn.setSandbox(false) // the library uses outside-sandbox features
    job.setDefinition(defn)
    job.setConcurrentBuild(true)
    job.description = 'Exercises all public module interfaces of apex-ci-library'
    println "[apex-init] Job ${jobName} definition updated (${jenkinsfile.length()} bytes)"
} else {
    println "[apex-init] WARNING: ${jenkinsfile} not found"
}

inst.save()

// 2) Trigger the first build asynchronously so we don't block Jenkins boot
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
