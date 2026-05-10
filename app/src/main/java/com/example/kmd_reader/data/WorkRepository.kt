package com.example.kmd_reader.data

import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

interface WorkRepository {
    fun listWorks(): List<Work>
    fun getWork(id: String): Work?
    fun listIssues(workId: String): List<ScriptIssue>
}
