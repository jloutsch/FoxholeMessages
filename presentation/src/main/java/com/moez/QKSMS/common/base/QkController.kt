package dev.octoshrimpy.quik.common.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import com.bluelinelabs.conductor.archlifecycle.LifecycleController
import dev.octoshrimpy.quik.R
import kotlinx.coroutines.CoroutineScope

/**
 * Base Conductor screen with view-binding helpers. Collection happens in [bindScope], which
 * lives from attach to detach (see [AttachScope]).
 */
abstract class QkController<VB : ViewBinding, V : QkView<S>, S : Any, P : QkPresenter<V, S>> :
    LifecycleController(), QkView<S> {

    abstract var presenter: P

    private val attachScope = AttachScope()

    override val bindScope: CoroutineScope get() = attachScope.scope

    private val appCompatActivity: AppCompatActivity?
        get() = activity as? AppCompatActivity

    protected val themedActivity: QkThemedActivity?
        get() = activity as? QkThemedActivity

    private var _binding: VB? = null
    protected val binding: VB
        get() = requireNotNull(_binding)

    protected abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): VB

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        val vb = inflateBinding(inflater, container)
        _binding = vb
        return vb.root.also { onViewCreated() }
    }

    open fun onViewCreated() {
    }

    // Started before subclasses' onAttach code, which calls presenter.bindIntents(this)
    override fun onAttach(view: View) {
        attachScope.start()
        super.onAttach(view)
    }

    override fun onDetach(view: View) {
        attachScope.stop()
        super.onDetach(view)
    }

    fun setTitle(@StringRes titleId: Int) {
        setTitle(activity?.getString(titleId))
    }

    fun setTitle(title: CharSequence?) {
        activity?.title = title
        view?.findViewById<TextView>(R.id.toolbarTitle)?.text = title
    }

    fun showBackButton(show: Boolean) {
        appCompatActivity?.supportActionBar?.setDisplayHomeAsUpEnabled(show)
    }

    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onCleared()
    }

}
