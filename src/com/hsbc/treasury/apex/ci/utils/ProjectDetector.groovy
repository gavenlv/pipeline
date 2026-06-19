package com.hsbc.treasury.apex.ci.utils

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 项目语言检测工具。
 */

class ProjectDetector implements Serializable {
    private static final long serialVersionUID = 1L

    private final File projectDir

    ProjectDetector(File projectDir) {
        this.projectDir = projectDir ?: new File('.')
    }

    String detect() {
        if (hasFile('pom.xml') || hasFile('build.gradle') || hasFile('build.gradle.kts')) return 'java'
        if (hasFile('package.json')) return 'node'
        if (hasFile('pyproject.toml') || hasFile('setup.py') || hasFile('requirements.txt')) return 'python'
        if (hasFile('go.mod')) return 'go'
        if (hasFile('Cargo.toml')) return 'rust'
        if (hasFile('Makefile')) return 'make'
        return 'shell'
    }

    boolean hasFile(String name) {
        return new File(projectDir, name).exists()
    }
}
