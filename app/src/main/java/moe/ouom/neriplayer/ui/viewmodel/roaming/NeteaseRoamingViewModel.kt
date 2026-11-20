package moe.ouom.neriplayer.ui.viewmodel.roaming

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.core.roaming.NeteaseRoamingRuntime
import moe.ouom.neriplayer.core.roaming.NeteaseRoamingUiState
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingChoice

/**
 * 漫游页的状态入口，只做转发。
 *
 * 真正的状态机在 [NeteaseRoamingRuntime]：它必须活得比这个 ViewModel 更久，
 * 否则用户退出漫游页就会断流。
 */
class NeteaseRoamingViewModel(application: Application) : AndroidViewModel(application) {

    val uiState: StateFlow<NeteaseRoamingUiState> = NeteaseRoamingRuntime.state

    init {
        NeteaseRoamingRuntime.ensureCatalog()
    }

    fun select(choice: NeteaseRoamingChoice) = NeteaseRoamingRuntime.select(choice)

    fun start() = NeteaseRoamingRuntime.start()

    fun stop() = NeteaseRoamingRuntime.stop()

    fun blockCurrentSong() = NeteaseRoamingRuntime.blockCurrentSong()

    fun refreshCatalog() = NeteaseRoamingRuntime.ensureCatalog(force = true)
}
