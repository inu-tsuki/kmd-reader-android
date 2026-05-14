package com.example.kmd_reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kmd_reader.data.MockWorkRepository
import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.runtime.FakeReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KmdReaderViewModel(
    private val repository: WorkRepository = MockWorkRepository(),
    private val runtimeBridge: ReaderRuntimeBridge = FakeReaderRuntimeBridge()
) : ViewModel() {
    private val _state = MutableStateFlow(KmdReaderState())
    val state: StateFlow<KmdReaderState> = _state.asStateFlow()

    private val effects = Channel<KmdReaderEffect>(capacity = Channel.BUFFERED)
    val effectFlow = effects.receiveAsFlow()

    init {
        observeRuntimeEvents()
        refreshWorks()
    }

    fun onAction(action: KmdReaderAction) {
        when (action) {
            KmdReaderAction.RefreshWorks -> refreshWorks()
            is KmdReaderAction.OpenWork -> {
                reduce(action)
                loadIssues(action.workId)
            }
            KmdReaderAction.OpenReview -> {
                reduce(action)
                _state.value.deskStack.currentWorkId?.let { workId ->
                    loadIssues(workId)
                    requestRuntimeInspection(workId)
                }
            }
            KmdReaderAction.OpenReader -> openReader()
            KmdReaderAction.OpenImport -> {
                reduce(action)
                sendEffect(KmdReaderEffect.OpenImportPicker)
            }
            else -> reduce(action)
        }
    }

    private fun openReader() {
        val workId = _state.value.deskStack.currentWorkId
        val work = _state.value.selectedWork
        reduce(KmdReaderAction.OpenReader)

        if (workId == null || work == null) {
            sendEffect(KmdReaderEffect.ShowMessage("请先选择一个 KMD 作品。"))
            return
        }

        _state.update {
            it.copy(readerSession = ReaderSessionState.Loading(workId = workId))
        }
        sendEffect(KmdReaderEffect.LoadRuntime(workId))

        viewModelScope.launch {
            runCatching {
                runtimeBridge.load(ReaderLoadRequest(work = work))
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        readerSession = ReaderSessionState.Failed(
                            workId = workId,
                            message = error.message ?: "Reader Runtime 加载失败"
                        )
                    )
                }
            }
        }
    }

    private fun observeRuntimeEvents() {
        viewModelScope.launch {
            runtimeBridge.events.collect(::handleRuntimeEvent)
        }
        viewModelScope.launch {
            runtimeBridge.attach()
        }
    }

    private fun handleRuntimeEvent(event: ReaderRuntimeEvent) {
        when (event) {
            is ReaderRuntimeEvent.Ready -> {
                _state.update {
                    it.copy(
                        readerSession = ReaderSessionState.Ready(
                            workId = event.workId,
                            progress = 0f,
                            isPlaying = false
                        )
                    )
                }
            }
            is ReaderRuntimeEvent.ProgressChanged -> {
                val currentSession = _state.value.readerSession
                val isPlaying = (currentSession as? ReaderSessionState.Ready)
                    ?.takeIf { it.workId == event.workId }
                    ?.isPlaying ?: false
                _state.update {
                    it.copy(
                        readerSession = ReaderSessionState.Ready(
                            workId = event.workId,
                            progress = event.progress,
                            isPlaying = isPlaying
                        )
                    )
                }
            }
            is ReaderRuntimeEvent.PlaybackStateChanged -> {
                val currentSession = _state.value.readerSession
                val progress = (currentSession as? ReaderSessionState.Ready)
                    ?.takeIf { it.workId == event.workId }
                    ?.progress ?: 0f
                _state.update {
                    it.copy(
                        readerSession = ReaderSessionState.Ready(
                            workId = event.workId,
                            progress = progress,
                            isPlaying = event.isPlaying
                        )
                    )
                }
            }
            is ReaderRuntimeEvent.InspectionReported -> {
                _state.update {
                    it.copy(
                        issuesByWorkId = it.issuesByWorkId + (
                            event.workId to mergeIssues(
                                current = it.issuesByWorkId[event.workId].orEmpty(),
                                incoming = event.issues
                            )
                            )
                    )
                }
            }
            is ReaderRuntimeEvent.Failed -> {
                _state.update {
                    it.copy(
                        readerSession = ReaderSessionState.Failed(
                            workId = event.workId ?: it.deskStack.currentWorkId.orEmpty(),
                            message = event.message
                        )
                    )
                }
            }
        }
    }

    private fun requestRuntimeInspection(workId: String) {
        val session = _state.value.readerSession
        if (session !is ReaderSessionState.Ready || session.workId != workId) {
            return
        }

        viewModelScope.launch {
            runCatching {
                runtimeBridge.setInspectionEnabled(true)
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("Runtime 检查暂时不可用"))
            }
        }
    }

    private fun refreshWorks(refresh: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingWorks = true, errorMessage = null) }
            runCatching {
                repository.listWorks(refresh = refresh)
            }.onSuccess { works ->
                _state.update {
                    it.copy(
                        works = works,
                        isLoadingWorks = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoadingWorks = false,
                        errorMessage = error.message ?: "加载作品列表失败"
                    )
                }
                sendEffect(KmdReaderEffect.ShowMessage("加载作品列表失败"))
            }
        }
    }

    private fun loadIssues(workId: String) {
        viewModelScope.launch {
            runCatching {
                repository.listIssues(workId = workId, refresh = true)
            }.onSuccess { issues ->
                _state.update {
                    it.copy(
                        issuesByWorkId = it.issuesByWorkId + (
                            workId to mergeIssues(
                                current = it.issuesByWorkId[workId].orEmpty(),
                                incoming = issues
                            )
                            )
                    )
                }
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("脚本检查结果暂时不可用"))
            }
        }
    }

    private fun reduce(action: KmdReaderAction) {
        _state.update { KmdReaderReducer.reduce(it, action) }
    }

    private fun sendEffect(effect: KmdReaderEffect) {
        viewModelScope.launch {
            effects.send(effect)
        }
    }

    override fun onCleared() {
        runtimeBridge.dispose()
        super.onCleared()
    }

    class Factory(
        private val repository: WorkRepository,
        private val runtimeBridge: ReaderRuntimeBridge
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(KmdReaderViewModel::class.java)) {
                return KmdReaderViewModel(repository, runtimeBridge) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    private fun mergeIssues(
        current: List<ScriptIssue>,
        incoming: List<ScriptIssue>
    ): List<ScriptIssue> {
        return (current + incoming).distinctBy { it.id }
    }
}
