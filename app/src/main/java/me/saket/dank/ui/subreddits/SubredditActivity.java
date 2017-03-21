package me.saket.dank.ui.subreddits;

import static me.saket.dank.utils.CommonUtils.defaultIfNull;
import static me.saket.dank.utils.RxUtils.applySchedulers;
import static me.saket.dank.utils.RxUtils.doOnStartAndFinish;
import static me.saket.dank.utils.RxUtils.logError;
import static me.saket.dank.utils.Views.setHeight;
import static me.saket.dank.utils.Views.setMarginStart;
import static me.saket.dank.utils.Views.setMarginTop;
import static me.saket.dank.utils.Views.statusBarHeight;
import static me.saket.dank.utils.Views.touchLiesOn;
import static rx.Observable.fromCallable;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.zagum.expandicon.ExpandIconView;

import net.dean.jraw.paginators.SubredditPaginator;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import me.saket.dank.R;
import me.saket.dank.data.DankRedditClient;
import me.saket.dank.data.DankSubreddit;
import me.saket.dank.data.RedditLink;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.DankPullCollapsibleActivity;
import me.saket.dank.ui.authentication.LoginActivity;
import me.saket.dank.ui.preferences.UserPreferencesActivity;
import me.saket.dank.ui.submission.SubmissionFragment;
import me.saket.dank.utils.DankSubmissionRequest;
import me.saket.dank.utils.Keyboards;
import me.saket.dank.widgets.DankToolbar;
import me.saket.dank.widgets.InboxUI.ExpandablePageLayout;
import me.saket.dank.widgets.InboxUI.InboxRecyclerView;
import me.saket.dank.widgets.InboxUI.IndependentExpandablePageLayout;
import me.saket.dank.widgets.ToolbarExpandableSheet;
import rx.Subscription;

public class SubredditActivity extends DankPullCollapsibleActivity implements SubmissionFragment.Callbacks {

    private static final int REQUEST_CODE_LOGIN = 100;
    private static final String KEY_INITIAL_SUBREDDIT_LINK = "initialSubredditLink";
    private static final String KEY_ACTIVE_SUBREDDIT = "activeSubreddit";

    @BindView(R.id.subreddit_root) IndependentExpandablePageLayout contentPage;
    @BindView(R.id.toolbar) DankToolbar toolbar;
    @BindView(R.id.subreddit_toolbar_status_bar_space) View statusBarSpaceView;
    @BindView(R.id.subreddit_toolbar_title) TextView toolbarTitleView;
    @BindView(R.id.subreddit_toolbar_title_arrow) ExpandIconView toolbarTitleArrowView;
    @BindView(R.id.subreddit_toolbar_title_container) ViewGroup toolbarTitleContainer;
    @BindView(R.id.subreddit_toolbar_container) ViewGroup toolbarContainer;
    @BindView(R.id.subreddit_submission_list) InboxRecyclerView submissionList;
    @BindView(R.id.subreddit_submission_page) ExpandablePageLayout submissionPage;
    @BindView(R.id.subreddit_toolbar_expandable_sheet) ToolbarExpandableSheet toolbarSheet;
    @BindView(R.id.subreddit_progress) View progressView;

    private DankSubreddit activeSubreddit;
    private SubmissionFragment submissionFragment;
    private SubRedditSubmissionsAdapter submissionsAdapter;

    /**
     * @param expandFromShape The initial shape from where this Activity will begin its entry expand animation.
     */
    public static void start(Context context, RedditLink.Subreddit subredditLink, Rect expandFromShape) {
        Intent intent = new Intent(context, SubredditActivity.class);
        intent.putExtra(KEY_INITIAL_SUBREDDIT_LINK, subredditLink);
        intent.putExtra(KEY_EXPAND_FROM_SHAPE, expandFromShape);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean isPullCollapsible = !isTaskRoot();
        setPullToCollapseEnabled(isPullCollapsible);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subreddit);
        ButterKnife.bind(this);
        setupContentExpandablePage(contentPage);

        // Add top-margin to make room for the status bar.
        int statusBarHeight = statusBarHeight(getResources());
        setHeight(statusBarSpaceView, statusBarHeight);
        setMarginTop(submissionList, statusBarHeight);

        findAndSetupToolbar();
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        if (isPullCollapsible) {
            toolbar.setNavigationIcon(R.drawable.ic_toolbar_close_24dp);
            setMarginStart(toolbarTitleView, getResources().getDimensionPixelSize(R.dimen.subreddit_toolbar_title_start_margin_with_nav_icon));

            contentPage.setNestedExpandablePage(submissionPage);
            expandFrom(getIntent().getParcelableExtra(KEY_EXPAND_FROM_SHAPE));
        }
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Setup submission list.
        submissionList.setLayoutManager(submissionList.createLayoutManager());
        submissionList.setItemAnimator(new DefaultItemAnimator());
        submissionList.setExpandablePage(submissionPage, toolbarContainer);

        contentPage.setPullToCollapseIntercepter((event, downX, downY, upwardPagePull) ->
                touchLiesOn(submissionList, downX, downY) && !touchLiesOn(toolbarContainer, downX, downY)
                        && submissionList.canScrollVertically(upwardPagePull ? 1 : -1)
        );

        if (savedInstanceState != null) {
            submissionList.handleOnRestoreInstanceState(savedInstanceState);
        }

        submissionsAdapter = new SubRedditSubmissionsAdapter();
        submissionsAdapter.setOnItemClickListener((submission, submissionItemView, submissionId) -> {
            DankSubmissionRequest submissionRequest = DankSubmissionRequest.builder(submission.getId())
                    .commentSort(defaultIfNull(submission.getSuggestedSort(), DankRedditClient.DEFAULT_COMMENT_SORT))
                    .build();
            submissionFragment.populateUi(submission, submissionRequest);

            submissionPage.post(() -> {
                // Posing the expand() call to the page's message queue seems to result in a smoother
                // expand animation. I assume this is because the expand() call only executes once the
                // page is done updating its views for this new submission. This might be a placebo too
                // and without enough testing I cannot be sure about this.
                submissionList.expandItem(submissionList.indexOfChild(submissionItemView), submissionId);
            });
        });
        submissionList.setAdapter(submissionsAdapter);

        // Setup submission page.
        submissionFragment = (SubmissionFragment) getSupportFragmentManager().findFragmentById(submissionPage.getId());
        if (submissionFragment == null) {
            submissionFragment = SubmissionFragment.create();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(submissionPage.getId(), submissionFragment)
                .commit();

        // Get frontpage (or retained subreddit's) submissions.
        if (savedInstanceState != null) {
            activeSubreddit = savedInstanceState.getParcelable(KEY_ACTIVE_SUBREDDIT);
        } else if (getIntent().hasExtra(KEY_INITIAL_SUBREDDIT_LINK)) {
            activeSubreddit = DankSubreddit.create(getIntent().<RedditLink.Subreddit>getParcelableExtra(KEY_INITIAL_SUBREDDIT_LINK).name());
        } else {
            //activeSubreddit = DankSubreddit.createFrontpage(getString(R.string.frontpage_subreddit_name));
            activeSubreddit = DankSubreddit.create("Supapp");
        }
        loadSubmissions(activeSubreddit);

        setupToolbarSheet();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        submissionList.handleOnSaveInstance(outState);
        outState.putParcelable(KEY_ACTIVE_SUBREDDIT, activeSubreddit);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void setTitle(CharSequence title) {
        toolbarTitleView.setText(title);
    }

    public void setTitle(DankSubreddit subreddit) {
        setTitle(subreddit.isFrontpage()
                ? getString(R.string.app_name)
                : getString(R.string.subreddit_name_r_prefix, subreddit.displayName()));
    }

    private void loadSubmissions(DankSubreddit subreddit) {
        activeSubreddit = subreddit;
        submissionsAdapter.updateData(null);

        setTitle(subreddit);

        SubredditPaginator subredditPaginator = Dank.reddit().subredditPaginator(subreddit.name());
        Subscription subscription = Dank.reddit()
                .withAuth(fromCallable(() -> subredditPaginator.next()))
                .compose(applySchedulers())
                .compose(doOnStartAndFinish(start -> progressView.setVisibility(start ? View.VISIBLE : View.GONE)))
                .subscribe(submissionsAdapter, logError("Couldn't get front-page"));
        unsubscribeOnDestroy(subscription);
    }

    private void setupToolbarSheet() {
        toolbar.setBackground(null);
        toolbarSheet.hideOnOutsideTouch(submissionList);
        toolbarSheet.setStateChangeListener(state -> {
            switch (state) {
                case EXPANDING:
                    if (isSubredditPickerVisible()) {
                        // When subreddit picker is showing, we'll show a "configure subreddits" button in the toolbar.
                        invalidateOptionsMenu();

                    } else if (isUserProfileSheetVisible()) {
                        setTitle(getString(R.string.user_name_u_prefix, Dank.reddit().loggedInUserName()));
                    }

                    toolbarTitleArrowView.setState(ExpandIconView.LESS, true);
                    break;

                case EXPANDED:
                    break;

                case COLLAPSING:
                    if (isSubredditPickerVisible()) {
                        Keyboards.hide(this, toolbarSheet);
                        invalidateOptionsMenu();

                    } else if (isUserProfileSheetVisible()) {
                        setTitle(activeSubreddit);
                    }

                    toolbarTitleArrowView.setState(ExpandIconView.MORE, true);
                    break;

                case COLLAPSED:
                    toolbarSheet.removeAllViews();
                    toolbarSheet.collapse();
                    break;
            }
        });
    }

// ======== NAVIGATION ======== //

    @Override
    public void onClickSubmissionToolbarUp() {
        submissionList.collapse();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(isSubredditPickerExpanded() ? R.menu.menu_subreddit_picker : R.menu.menu_subreddit, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_user_profile:
                if (Dank.reddit().isUserLoggedIn()) {
                    onClickUserProfileMenu();
                } else {
                    LoginActivity.startForResult(this, REQUEST_CODE_LOGIN);
                }
                return true;

            case R.id.action_preferences:
                UserPreferencesActivity.start(this);
                return true;

            case R.id.action_manage_subreddits:
                // TODO: 05/03/17 Open Manage-Subreddits preferences screen.
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @OnClick(R.id.subreddit_toolbar_title)
    void onClickSubredditPicker() {
        if (toolbarSheet.isExpandedOrExpanding()) {
            toolbarSheet.collapse();

        } else {
            SubredditPickerSheetView pickerSheet = SubredditPickerSheetView.showIn(toolbarSheet);
            pickerSheet.post(() -> toolbarSheet.expand());
            pickerSheet.setOnSubredditClickListener(subreddit -> {
                toolbarSheet.collapse();
                if (!subreddit.equals(activeSubreddit)) {
                    loadSubmissions(subreddit);
                }
            });
        }
    }

    void onClickUserProfileMenu() {
        if (toolbarSheet.isExpandedOrExpanding()) {
            toolbarSheet.collapse();

        } else {
            UserProfileSheetView pickerSheet = UserProfileSheetView.showIn(toolbarSheet);
            pickerSheet.post(() -> toolbarSheet.expand());
        }
    }

    /**
     * Whether the subreddit picker is visible, albeit partially.
     */
    private boolean isSubredditPickerVisible() {
        return !toolbarSheet.isCollapsed() && toolbarSheet.getChildAt(0) instanceof SubredditPickerSheetView;
    }

    /**
     * Whether the subreddit picker is "fully" visible.
     */
    private boolean isSubredditPickerExpanded() {
        return toolbarSheet.isExpandedOrExpanding() && toolbarSheet.getChildAt(0) instanceof SubredditPickerSheetView;
    }

    private boolean isUserProfileSheetVisible() {
        return !toolbarSheet.isCollapsed() && toolbarSheet.getChildAt(0) instanceof UserProfileSheetView;
    }

    @Override
    public void onBackPressed() {
        if (submissionPage.isExpandedOrExpanding()) {
            boolean backPressHandled = submissionFragment.handleBackPress();
            if (!backPressHandled) {
                submissionList.collapse();
            }

        } else if (!toolbarSheet.isCollapsed()) {
            toolbarSheet.collapse();

        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_LOGIN && resultCode == RESULT_OK) {
            // Show user's profile.
            onClickUserProfileMenu();

            // Reload submissions if we're on the frontpage.
            if (activeSubreddit.isFrontpage()) {
                loadSubmissions(activeSubreddit);
            }

        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

}
