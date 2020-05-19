package ru.orangesoftware.main.fragments

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ListAdapter
import android.widget.TextView
import android.widget.Toast
import ru.orangesoftware.financisto.R
import ru.orangesoftware.financisto.activity.*
import ru.orangesoftware.financisto.activity.FilterState.*
import ru.orangesoftware.financisto.adapter.BudgetListAdapter
import ru.orangesoftware.financisto.blotter.BlotterFilter
import ru.orangesoftware.financisto.datetime.PeriodType
import ru.orangesoftware.financisto.db.BudgetsTotalCalculator
import ru.orangesoftware.financisto.filter.Criteria
import ru.orangesoftware.financisto.filter.DateTimeCriteria
import ru.orangesoftware.financisto.filter.WhereFilter
import ru.orangesoftware.financisto.model.Budget
import ru.orangesoftware.financisto.model.Total
import ru.orangesoftware.financisto.utils.RecurUtils
import ru.orangesoftware.financisto.utils.RecurUtils.RecurInterval
import ru.orangesoftware.financisto.utils.Utils
import ru.orangesoftware.main.base.AbstractListFragment
import java.util.*

class BudgetListFragment: AbstractListFragment(R.layout.budget_list) {
    companion object {
        val NEW_BUDGET_REQUEST = 1
        val EDIT_BUDGET_REQUEST = 2
        val VIEW_BUDGET_REQUEST = 3
        val FILTER_BUDGET_REQUEST = 4
    }

    private var bFilter: ImageButton? = null

    private var filter = WhereFilter.empty()

    private var budgets: ArrayList<Budget>? = null
    private var handler: Handler? = null

    override fun internalOnCreate(savedInstanceState: Bundle?) {
        super.internalOnCreate(savedInstanceState)
        val totalText = view?.findViewById<TextView>(R.id.total)
        totalText?.setOnClickListener { view: View? -> showTotals() }
        bFilter = view?.findViewById<ImageButton>(R.id.bFilter)
        bFilter!!.setOnClickListener { v: View? ->
            val intent = Intent(activity!!, DateFilterActivity::class.java)
            filter.toIntent(intent)
            startActivityForResult(intent, FILTER_BUDGET_REQUEST)
        }
        if (filter.isEmpty) {
            filter = WhereFilter.fromSharedPreferences(activity!!.getPreferences(0))
        }
        if (filter.isEmpty) {
            filter.put(DateTimeCriteria(PeriodType.THIS_MONTH))
        }
        budgets = db!!.getAllBudgets(filter)
        handler = Handler()
        applyFilter()
        calculateTotals()
    }

    private fun showTotals() {
        if(activity == null) {
            return
        }
        val intent = Intent(activity!!, BudgetListTotalsDetailsActivity::class.java)
        filter.toIntent(intent)
        startActivityForResult(intent, -1)
    }

    private fun saveFilter() {
        if(activity == null) {
            return
        }
        val preferences: SharedPreferences = activity!!.getPreferences(0)
        filter.toSharedPreferences(preferences)
        applyFilter()
        recreateCursor()
    }

    private fun applyFilter() {
        if(activity == null) {
            return
        }
        updateFilterColor(activity!!, filter, bFilter)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val unmaskedRequestCode = requestCode and 0x0000ffff
        if (unmaskedRequestCode == FILTER_BUDGET_REQUEST) {
            if (unmaskedRequestCode == Activity.RESULT_FIRST_USER) {
                filter.clear()
            } else if (unmaskedRequestCode == Activity.RESULT_OK) {
                val periodType = data?.getStringExtra(DateFilterActivity.EXTRA_FILTER_PERIOD_TYPE)
                if(periodType != null) {
                    val p = PeriodType.valueOf(periodType)
                    if (PeriodType.CUSTOM === p) {
                        val periodFrom = data.getLongExtra(DateFilterActivity.EXTRA_FILTER_PERIOD_FROM, 0)
                        val periodTo = data.getLongExtra(DateFilterActivity.EXTRA_FILTER_PERIOD_TO, 0)
                        filter.put(DateTimeCriteria(periodFrom, periodTo))
                    } else {
                        filter.put(DateTimeCriteria(p))
                    }
                }
            }
            saveFilter()
        }
        recreateCursor()
    }

    override fun createAdapter(cursor: Cursor?): ListAdapter? {
        if(activity == null) {
            return null
        }
        return BudgetListAdapter(activity!!, budgets)
    }

    override fun createCursor(): Cursor? {
        return null
    }

    override fun recreateCursor() {
        budgets = db!!.getAllBudgets(filter)
        updateAdapter()
        calculateTotals()
    }

    private fun updateAdapter() {
        (adapter as BudgetListAdapter).setBudgets(budgets)
    }

    private var totalCalculationTask: BudgetTotalsCalculationTask? = null

    private fun calculateTotals() {
        if (totalCalculationTask != null) {
            totalCalculationTask!!.stop()
            totalCalculationTask!!.cancel(true)
        }
        val totalText = view?.findViewById<TextView>(R.id.total)
        if(totalText != null) {
            totalCalculationTask = BudgetTotalsCalculationTask(totalText)
            totalCalculationTask!!.execute(null)
        }
    }

    override fun addItem() {
        if(activity == null) {
            return
        }
        val intent = Intent(activity!!, BudgetActivity::class.java)
        startActivityForResult(intent, NEW_BUDGET_REQUEST)
    }

    override fun deleteItem(v: View?, position: Int, id: Long) {
        if(activity == null) {
            return
        }
        val b = db!!.load(Budget::class.java, id)
        if (b.parentBudgetId > 0) {
            AlertDialog.Builder(activity!!)
                    .setMessage(R.string.delete_budget_recurring_select)
                    .setPositiveButton(R.string.delete_budget_one_entry) { arg0, arg1 ->
                        db!!.deleteBudgetOneEntry(id)
                        recreateCursor()
                    }
                    .setNeutralButton(R.string.delete_budget_all_entries) { arg0, arg1 ->
                        db!!.deleteBudget(b.parentBudgetId)
                        recreateCursor()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
        } else {
            val recur = RecurUtils.createFromExtraString(b.recur)
            AlertDialog.Builder(activity!!)
                    .setMessage(if (recur.interval === RecurInterval.NO_RECUR) R.string.delete_budget_confirm else R.string.delete_budget_recurring_confirm)
                    .setPositiveButton(R.string.yes) { arg0, arg1 ->
                        db!!.deleteBudget(id)
                        recreateCursor()
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
        }
    }

    override fun editItem(v: View?, position: Int, id: Long) {
        if(activity == null) {
            return
        }
        val b = db!!.load(Budget::class.java, id)
        val recur = b.getRecur()
        if (recur.interval !== RecurInterval.NO_RECUR) {
            val t: Toast = Toast.makeText(activity!!, R.string.edit_recurring_budget, Toast.LENGTH_LONG)
            t.show()
        }
        val intent = Intent(activity!!, BudgetActivity::class.java)
        intent.putExtra(BudgetActivity.BUDGET_ID_EXTRA, if (b.parentBudgetId > 0) b.parentBudgetId else id)
        startActivityForResult(intent, EDIT_BUDGET_REQUEST)
    }

    override fun viewItem(v: View?, position: Int, id: Long) {
        if(activity == null) {
            return
        }
        val b = db!!.load(Budget::class.java, id)
        val intent = Intent(activity!!, BudgetBlotterActivity::class.java)
        Criteria.eq(BlotterFilter.BUDGET_ID, id.toString())
                .toIntent(b.title, intent)
        startActivityForResult(intent, VIEW_BUDGET_REQUEST)
    }

    inner class BudgetTotalsCalculationTask(private val totalText: TextView) : AsyncTask<Void?, Total?, Total>() {
        @Volatile
        private var isRunning = true

        override fun doInBackground(vararg p0: Void?): Total {
            return try {
                val c = BudgetsTotalCalculator(db, budgets)
                c.updateBudgets(handler)
                c.calculateTotalInHomeCurrency()
            } catch (ex: Exception) {
                Log.e("BudgetTotals", "Unexpected error", ex)
                Total.ZERO
            }
        }

        override fun onPostExecute(result: Total) {
            if (isRunning && this@BudgetListFragment.activity != null) {
                val u = Utils(this@BudgetListFragment.activity!!)
                u.setTotal(totalText, result)
                (adapter as BudgetListAdapter).notifyDataSetChanged()
            }
        }

        fun stop() {
            isRunning = false
        }
    }
}