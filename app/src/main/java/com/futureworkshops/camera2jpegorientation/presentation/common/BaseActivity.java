package com.futureworkshops.camera2jpegorientation.presentation.common;

import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;


import com.futureworkshops.camera2jpegorientation.R;

import butterknife.BindView;

/**
 * Created by stelian on 20/10/2016.
 */

public class BaseActivity extends AppCompatActivity {
    @Nullable
    @BindView(R.id.toolbar)
    protected Toolbar mToolbar;

    protected void setupToolbar(boolean showHomeAsUp) {
        if (mToolbar != null) {

            setSupportActionBar(mToolbar);
            getSupportActionBar().setDisplayHomeAsUpEnabled(showHomeAsUp);

            mToolbar.setTitleTextColor(getResources().getColor(android.R.color.white));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }
}
