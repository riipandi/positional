package io.trewartha.positional.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.BottomNavigationView
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewPager
import android.support.v7.app.AppCompatActivity
import android.support.v7.app.AppCompatDelegate
import android.view.MenuItem
import com.google.firebase.auth.FirebaseAuth
import io.trewartha.positional.R
import io.trewartha.positional.common.GlideApp
import io.trewartha.positional.ui.auth.SignInActivity
import io.trewartha.positional.ui.profile.ProfileActivity
import kotlinx.android.synthetic.main.main_activity.*


class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SIGN_IN = 1

        init {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private val firebaseAuth = FirebaseAuth.getInstance()

    private lateinit var fragmentPagerAdapter: MainFragmentPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        fragmentPagerAdapter = MainFragmentPagerAdapter(supportFragmentManager)
        viewPager.adapter = fragmentPagerAdapter
        viewPager.addOnPageChangeListener(PageChangeListener())

        bottomNavigationView.setOnNavigationItemSelectedListener(NavigationItemSelectedListener())

        userPhotoImageView.setOnClickListener { onUserPhotoClick() }
        loadUserPhoto()

        if (!signedIn()) {
            val signInIntent = Intent(this, SignInActivity::class.java)
            startActivityForResult(signInIntent, REQUEST_CODE_SIGN_IN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SIGN_IN -> loadUserPhoto()
            else -> fragmentPagerAdapter.notifyDataSetChanged()
        }
    }

    private fun loadUserPhoto() {
        val url = firebaseAuth.currentUser?.photoUrl
        val textColor = ContextCompat.getColor(this, R.color.gray4)
        val backgroundColor = ContextCompat.getColor(this, R.color.white)
        val unknownDrawable = TextDrawable(this, "?", textColor, backgroundColor)
        GlideApp.with(this)
                .load(url)
                .placeholder(unknownDrawable)
                .error(unknownDrawable)
                .circleCrop()
                .into(userPhotoImageView)
    }

    private fun onUserPhotoClick() {
        val profileIntent = ProfileActivity.IntentBuilder(this).build()
        val options = ActivityOptions.makeSceneTransitionAnimation(
                this,
                userPhotoImageView,
                getString(R.string.transition_user_photo)
        ).toBundle()
        startActivity(profileIntent, options)
    }

    private fun signedIn() = firebaseAuth.currentUser != null

    private inner class NavigationItemSelectedListener : BottomNavigationView.OnNavigationItemSelectedListener {
        override fun onNavigationItemSelected(item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.mapNavMenuItem -> viewPager.currentItem = 0
                R.id.tracksNavMenuItem -> viewPager.currentItem = 1
                R.id.positionNavMenuItem -> viewPager.currentItem = 2
                R.id.compassNavMenuItem -> viewPager.currentItem = 3
            }
            return true
        }
    }

    private inner class PageChangeListener : ViewPager.OnPageChangeListener {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {

        }

        override fun onPageSelected(position: Int) {
            for (i in 0 until bottomNavigationView.menu.size()) {
                val checked = i == position
                bottomNavigationView.menu.getItem(i).isChecked = checked
            }
        }

        override fun onPageScrollStateChanged(state: Int) {

        }
    }
}
