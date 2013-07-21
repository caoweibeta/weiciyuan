package org.qii.weiciyuan.ui.browser;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.text.Html;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import org.qii.weiciyuan.R;
import org.qii.weiciyuan.bean.CommentListBean;
import org.qii.weiciyuan.bean.GeoBean;
import org.qii.weiciyuan.bean.MessageBean;
import org.qii.weiciyuan.bean.RepostListBean;
import org.qii.weiciyuan.bean.android.AsyncTaskLoaderResult;
import org.qii.weiciyuan.support.asyncdrawable.MsgDetailReadWorker;
import org.qii.weiciyuan.support.error.WeiboException;
import org.qii.weiciyuan.support.lib.LongClickableLinkMovementMethod;
import org.qii.weiciyuan.support.lib.MyAsyncTask;
import org.qii.weiciyuan.support.lib.WeiboDetailImageView;
import org.qii.weiciyuan.support.lib.pulltorefresh.PullToRefreshBase;
import org.qii.weiciyuan.support.lib.pulltorefresh.PullToRefreshListView;
import org.qii.weiciyuan.support.utils.GlobalContext;
import org.qii.weiciyuan.support.utils.Utility;
import org.qii.weiciyuan.ui.adapter.BrowserWeiboMsgCommentAndRepostAdapter;
import org.qii.weiciyuan.ui.interfaces.AbstractAppActivity;
import org.qii.weiciyuan.ui.interfaces.AbstractAppFragment;
import org.qii.weiciyuan.ui.loader.CommentsByIdMsgLoader;
import org.qii.weiciyuan.ui.loader.RepostByIdMsgLoader;
import org.qii.weiciyuan.ui.userinfo.UserInfoActivity;

/**
 * User: qii
 * Date: 12-9-1
 */
public class BrowserWeiboMsgFragment extends AbstractAppFragment {

    private MessageBean msg;

    private View mRootview;
    private BrowserWeiboMsgLayout layout;

    private UpdateMessageTask updateMsgTask;
    private GetGoogleLocationInfoTask geoTask;
    private MsgDetailReadWorker picTask;

    private Handler handler = new Handler();

    private ListView listView;
    private BrowserWeiboMsgCommentAndRepostAdapter adapter;

    private CommentListBean commentList = new CommentListBean();
    private RepostListBean repostList = new RepostListBean();

    private TextView repostTab;
    private TextView commentTab;

    private static final int NEW_COMMENT_LOADER_ID = 1;
    private static final int OLD_COMMENT_LOADER_ID = 2;

    private static final int NEW_REPOST_LOADER_ID = 3;
    private static final int OLD_REPOST_LOADER_ID = 4;

    private boolean isCommentList = true;

    private static class BrowserWeiboMsgLayout {
        TextView username;
        TextView content;
        TextView recontent;
        TextView time;
        TextView location;
        TextView source;

        MapView mapView;

        ImageView avatar;
        WeiboDetailImageView content_pic;
        WeiboDetailImageView repost_pic;

        LinearLayout repost_layout;

        TextView comment_count;
        TextView repost_count;
        View count_layout;

    }

    public BrowserWeiboMsgFragment() {
    }


    public BrowserWeiboMsgFragment(MessageBean msg) {
        this.msg = msg;
    }

    private boolean hasGpsInfo() {
        return (this.msg != null) && (this.msg.getGeo() != null);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (hasGpsInfo())
            layout.mapView.onSaveInstanceState(outState);
        outState.putParcelable("msg", msg);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (hasGpsInfo())
                MapsInitializer.initialize(getActivity());
        } catch (GooglePlayServicesNotAvailableException impossible) {
                      /* Impossible */
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);

        switch (getCurrentState(savedInstanceState)) {
            case FIRST_TIME_START:
                if (Utility.isTaskStopped(updateMsgTask)) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            updateMsgTask = new UpdateMessageTask(BrowserWeiboMsgFragment.this, layout.content, layout.recontent, msg, false);
                            updateMsgTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
                        }
                    }, 2000);
                }
                buildViewData(true);
                loadNewCommentData();
                break;
            case SCREEN_ROTATE:
                //nothing
                break;
            case ACTIVITY_DESTROY_AND_CREATE:
                msg = (MessageBean) savedInstanceState.getParcelable("msg");
                buildViewData(true);
                break;
        }

        Loader loader = getLoaderManager().getLoader(NEW_COMMENT_LOADER_ID);
        if (loader != null) {
            getLoaderManager().initLoader(NEW_COMMENT_LOADER_ID, null, commentMsgCallback);
        }

        loader = getLoaderManager().getLoader(OLD_COMMENT_LOADER_ID);
        if (loader != null) {
            getLoaderManager().initLoader(OLD_COMMENT_LOADER_ID, null, commentMsgCallback);
        }

    }


    //android has a bug,I am tired. I use another color and disable underline for link,but when I open "dont save activity" in
    //developer option,click the link to open another activity, then press back,this fragment is restored,
    //but the link color is restored to android own blue color,not my custom color,the underline appears
    //the workaround is set textview value in onresume() method
    @Override
    public void onResume() {
        super.onResume();
//        buildViewData(false);
        if (hasGpsInfo())
            layout.mapView.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Utility.cancelTasks(updateMsgTask, geoTask, picTask);

        layout.avatar.setImageDrawable(null);
        layout.content_pic.setImageDrawable(null);
        layout.repost_pic.setImageDrawable(null);
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        PullToRefreshListView pullToRefreshListView = new PullToRefreshListView(getActivity());
        pullToRefreshListView.setMode(PullToRefreshBase.Mode.DISABLED);
        pullToRefreshListView.setOnLastItemVisibleListener(new PullToRefreshBase.OnLastItemVisibleListener() {
            @Override
            public void onLastItemVisible() {
                if (isCommentList) {
                    loadOldCommentData();
                } else {
                    loadOldRepostData();
                }
            }
        });

        listView = pullToRefreshListView.getRefreshableView();

        View header = inflater.inflate(R.layout.browserweibomsgactivity_layout, listView, false);
        listView.addHeaderView(header);

        View switchView = inflater.inflate(R.layout.browserweibomsgfragment_switch_list_type_header, listView, false);
        listView.addHeaderView(switchView);

        switchView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //empty
            }
        });

        repostTab = (TextView) switchView.findViewById(R.id.repost);
        commentTab = (TextView) switchView.findViewById(R.id.comment);

        repostTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isCommentList = false;
                adapter.switchToRepostType();
                repostTab.setTextColor(getResources().getColor(R.color.orange));
                commentTab.setTextColor(getResources().getColor(R.color.black));
                if (repostList.getSize() == 0) {
                    loadNewRepostData();
                }
            }
        });

        commentTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isCommentList = true;
                adapter.switchToCommentType();
                commentTab.setTextColor(getResources().getColor(R.color.orange));
                repostTab.setTextColor(getResources().getColor(R.color.black));
            }
        });
        commentTab.setTextColor(getResources().getColor(R.color.orange));

        initView(header, savedInstanceState);
        adapter = new BrowserWeiboMsgCommentAndRepostAdapter(this, listView, commentList.getItemList(), repostList.getItemList());
        listView.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        listView.setHeaderDividersEnabled(true);
        return pullToRefreshListView;
    }

    private void initView(View view, Bundle savedInstanceState) {
        layout = new BrowserWeiboMsgLayout();
        layout.username = (TextView) view.findViewById(R.id.username);
        layout.content = (TextView) view.findViewById(R.id.content);
        layout.recontent = (TextView) view.findViewById(R.id.repost_content);
        layout.time = (TextView) view.findViewById(R.id.time);
        layout.location = (TextView) view.findViewById(R.id.location);
        layout.source = (TextView) view.findViewById(R.id.source);
        if (hasGpsInfo()) {
            ViewStub stub = (ViewStub) view.findViewById(R.id.stub);
            View inflated = stub.inflate();
            layout.mapView = (MapView) inflated.findViewById(R.id.location_mv);
        }
        if (savedInstanceState != null && hasGpsInfo()) {
            MessageBean msg = (MessageBean) savedInstanceState.getParcelable("msg");
            savedInstanceState.remove("msg");
            layout.mapView.onCreate(savedInstanceState);
            savedInstanceState.putParcelable("msg", msg);
        } else if (hasGpsInfo()) {
            layout.mapView.onCreate(savedInstanceState);
        }
        layout.comment_count = (TextView) view.findViewById(R.id.comment_count);
        layout.repost_count = (TextView) view.findViewById(R.id.repost_count);
        layout.count_layout = view.findViewById(R.id.count_layout);

        layout.avatar = (ImageView) view.findViewById(R.id.avatar);
        layout.content_pic = (WeiboDetailImageView) view.findViewById(R.id.content_pic);
        layout.repost_pic = (WeiboDetailImageView) view.findViewById(R.id.repost_content_pic);


        layout.repost_layout = (LinearLayout) view.findViewById(R.id.repost_layout);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        layout.content_pic.setOnClickListener(picOnClickListener);
        layout.repost_pic.setOnClickListener(picOnClickListener);

        layout.location.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Utility.isGooglePlaySafe(getActivity())) {
                    GeoBean bean = msg.getGeo();
                    Intent intent = new Intent(getActivity(), AppMapActivity.class);
                    intent.putExtra("lat", bean.getLat());
                    intent.putExtra("lon", bean.getLon());
                    if (!String.valueOf(bean.getLat() + "," + bean.getLon()).equals(layout.location.getText()))
                        intent.putExtra("locationStr", layout.location.getText());
                    startActivity(intent);
                } else {
                    GeoBean bean = msg.getGeo();
                    String geoUriString = "geo:" + bean.getLat() + "," + bean.getLon() + "?q=" + layout.location.getText();
                    Uri geoUri = Uri.parse(geoUriString);
                    Intent mapCall = new Intent(Intent.ACTION_VIEW, geoUri);
                    if (Utility.isIntentSafe(getActivity(), mapCall)) {
                        startActivity(mapCall);
                    }

                }
            }
        });
        view.findViewById(R.id.first).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), UserInfoActivity.class);
                intent.putExtra("token", GlobalContext.getInstance().getSpecialToken());
                intent.putExtra("user", msg.getUser());
                startActivity(intent);
            }
        });
        layout.recontent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //This condition will satisfy only when it is not an autolinked text
                //onClick action
                boolean isNotLink = layout.recontent.getSelectionStart() == -1 && layout.recontent.getSelectionEnd() == -1;
                boolean isDeleted = msg.getRetweeted_status() == null || msg.getRetweeted_status().getUser() == null;

                if (isNotLink && !isDeleted) {
                    Intent intent = new Intent(getActivity(), BrowserWeiboMsgActivity.class);
                    intent.putExtra("token", GlobalContext.getInstance().getSpecialToken());
                    intent.putExtra("msg", msg.getRetweeted_status());
                    startActivity(intent);
                } else if (isNotLink && isDeleted) {
                    Toast.makeText(getActivity(), getString(R.string.cant_open_deleted_weibo), Toast.LENGTH_SHORT).show();
                }

            }
        });
    }

    public void buildViewData(final boolean refreshPic) {
        if (msg.getUser() != null) {
            if (TextUtils.isEmpty(msg.getUser().getRemark()))
                layout.username.setText(msg.getUser().getScreen_name());
            else
                layout.username.setText(msg.getUser().getScreen_name() + "(" + msg.getUser().getRemark() + ")");

            ((AbstractAppActivity) getActivity()).getBitmapDownloader().downloadAvatar(layout.avatar, msg.getUser());
        }
        layout.content.setText(msg.getListViewSpannableString());
        layout.content.setMovementMethod(LongClickableLinkMovementMethod.getInstance());

        layout.time.setText(msg.getTimeInFormat());

        if (msg.getGeo() != null) {
            if (Utility.isTaskStopped(geoTask)) {
                geoTask = new GetGoogleLocationInfoTask(getActivity(), msg.getGeo(), layout.mapView, layout.location);
                geoTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
        if (!TextUtils.isEmpty(msg.getSource())) {
            layout.source.setText(Html.fromHtml(msg.getSource()).toString());
        }

        //sina weibo official account can send repost message with picture, fuck sina weibo
        if (!TextUtils.isEmpty(msg.getBmiddle_pic()) && msg.getRetweeted_status() == null) {
            if (Utility.isTaskStopped(picTask)) {
                layout.content_pic.setVisibility(View.VISIBLE);

                if (refreshPic) {
                    picTask = new MsgDetailReadWorker(layout.content_pic, msg);
                    picTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
                }

            }
        }

        MessageBean repostMsg = msg.getRetweeted_status();

        layout.repost_layout.setVisibility(repostMsg != null ? View.VISIBLE : View.GONE);

        if (repostMsg != null) {
            //sina weibo official account can send repost message with picture, fuck sina weibo
            layout.content_pic.setVisibility(View.GONE);

            layout.repost_layout.setVisibility(View.VISIBLE);
            layout.recontent.setVisibility(View.VISIBLE);
            layout.recontent.setMovementMethod(LongClickableLinkMovementMethod.getInstance());
            if (repostMsg.getUser() != null) {
                layout.recontent.setText(repostMsg.getListViewSpannableString());
                buildRepostCount();
            } else {
                layout.recontent.setText(repostMsg.getListViewSpannableString());
            }
            if (!TextUtils.isEmpty(repostMsg.getBmiddle_pic())) {
                layout.repost_pic.setVisibility(View.VISIBLE);
                if (Utility.isTaskStopped(picTask)) {

                    if (refreshPic) {
                        picTask = new MsgDetailReadWorker(layout.repost_pic, msg.getRetweeted_status());
                        picTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
                    }


                }
            } else {
                layout.repost_pic.setVisibility(View.GONE);
            }
        }

        Utility.buildTabCount(commentTab, getString(R.string.comments), msg.getComments_count());
        Utility.buildTabCount(repostTab, getString(R.string.repost), msg.getReposts_count());

//        Utility.buildTabCount(getActivity().getActionBar().getTabAt(1), getString(R.string.comments), msg.getComments_count());
//        Utility.buildTabCount(getActivity().getActionBar().getTabAt(2), getString(R.string.repost), msg.getReposts_count());
    }

    private void buildRepostCount() {
        MessageBean repostBean = msg.getRetweeted_status();

        if (repostBean.getComments_count() == 0 && repostBean.getReposts_count() == 0) {
            layout.count_layout.setVisibility(View.GONE);
            return;
        } else {
            layout.count_layout.setVisibility(View.VISIBLE);
        }

        if (repostBean.getComments_count() > 0) {
            layout.comment_count.setVisibility(View.VISIBLE);
            layout.comment_count.setText(String.valueOf(repostBean.getComments_count()));
        } else {
            layout.comment_count.setVisibility(View.GONE);
        }

        if (repostBean.getReposts_count() > 0) {
            layout.repost_count.setVisibility(View.VISIBLE);
            layout.repost_count.setText(String.valueOf(repostBean.getReposts_count()));
        } else {
            layout.repost_count.setVisibility(View.GONE);
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_refresh:
                if (Utility.isTaskStopped(updateMsgTask)) {
                    updateMsgTask = new UpdateMessageTask(BrowserWeiboMsgFragment.this, layout.content, layout.recontent, msg, true);
                    updateMsgTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
                }
                loadNewCommentData();
                break;

        }
        return true;
    }

    private View.OnClickListener picOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Object object = v.getTag();
            if (object != null && (Boolean) object) {
                Intent intent = new Intent(getActivity(), BrowserBigPicActivity.class);
                if (!TextUtils.isEmpty(msg.getThumbnail_pic())) {
                    intent.putExtra("msg", msg);
                } else {
                    intent.putExtra("msg", msg.getRetweeted_status());
                }
                startActivity(intent);
            } else {
                if (picTask != null) {
                    picTask.cancel(true);
                }
                if (!TextUtils.isEmpty(msg.getThumbnail_pic())) {
                    picTask = new MsgDetailReadWorker(layout.content_pic, msg);
                    picTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
                } else {
                    picTask = new MsgDetailReadWorker(layout.repost_pic, msg.getRetweeted_status());
                    picTask.executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);

                }
            }
        }
    };

    public void loadNewCommentData() {
        getLoaderManager().destroyLoader(OLD_COMMENT_LOADER_ID);
        getLoaderManager().restartLoader(NEW_COMMENT_LOADER_ID, null, commentMsgCallback);
    }

    public void loadNewRepostData() {
        getLoaderManager().destroyLoader(OLD_REPOST_LOADER_ID);
        getLoaderManager().restartLoader(NEW_REPOST_LOADER_ID, null, repostMsgCallback);
    }

    public void loadOldCommentData() {
        getLoaderManager().destroyLoader(NEW_COMMENT_LOADER_ID);
        getLoaderManager().restartLoader(OLD_COMMENT_LOADER_ID, null, commentMsgCallback);
    }

    public void loadOldRepostData() {
        getLoaderManager().destroyLoader(NEW_REPOST_LOADER_ID);
        getLoaderManager().restartLoader(OLD_REPOST_LOADER_ID, null, repostMsgCallback);
    }

    protected LoaderManager.LoaderCallbacks<AsyncTaskLoaderResult<CommentListBean>> commentMsgCallback = new LoaderManager.LoaderCallbacks<AsyncTaskLoaderResult<CommentListBean>>() {

        @Override
        public Loader<AsyncTaskLoaderResult<CommentListBean>> onCreateLoader(int id, Bundle args) {
            String token = GlobalContext.getInstance().getSpecialToken();

            switch (id) {
                case NEW_COMMENT_LOADER_ID:
                    String sinceId = null;
                    return new CommentsByIdMsgLoader(getActivity(), msg.getId(), token, sinceId, null);
                case OLD_COMMENT_LOADER_ID:
                    String maxId = null;
                    if (commentList.getItemList().size() > 0) {
                        maxId = commentList.getItemList().get(commentList.getItemList().size() - 1).getId();
                    }
                    return new CommentsByIdMsgLoader(getActivity(), msg.getId(), token, null, maxId);
            }

            return null;
        }

        @Override
        public void onLoadFinished(Loader<AsyncTaskLoaderResult<CommentListBean>> loader, AsyncTaskLoaderResult<CommentListBean> result) {

            CommentListBean data = result != null ? result.data : null;
            WeiboException exception = result != null ? result.exception : null;
            Bundle args = result != null ? result.args : null;

            if (data != null) {
                Utility.buildTabCount(commentTab, getString(R.string.comments), data.getTotal_number());
            }

            switch (loader.getId()) {
                case NEW_COMMENT_LOADER_ID:

                    if (Utility.isAllNotNull(exception)) {
                        Toast.makeText(getActivity(), exception.getError(), Toast.LENGTH_SHORT).show();
                    } else {
                        if (data != null && data.getSize() > 0) {
                            commentList.replaceAll(data);
                            adapter.notifyDataSetChanged();

                        }
                    }
                    break;
                case OLD_COMMENT_LOADER_ID:

                    if (Utility.isAllNotNull(exception)) {
                        Toast.makeText(getActivity(), exception.getError(), Toast.LENGTH_SHORT).show();
                    } else {
                        commentList.addOldData(data);
                        adapter.notifyDataSetChanged();
                    }
                    break;
            }
            getLoaderManager().destroyLoader(loader.getId());
        }

        @Override
        public void onLoaderReset(Loader<AsyncTaskLoaderResult<CommentListBean>> loader) {

        }
    };


    protected LoaderManager.LoaderCallbacks<AsyncTaskLoaderResult<RepostListBean>> repostMsgCallback = new LoaderManager.LoaderCallbacks<AsyncTaskLoaderResult<RepostListBean>>() {

        @Override
        public Loader<AsyncTaskLoaderResult<RepostListBean>> onCreateLoader(int id, Bundle args) {
            String token = GlobalContext.getInstance().getSpecialToken();

            switch (id) {
                case NEW_REPOST_LOADER_ID:
                    String sinceId = null;
                    return new RepostByIdMsgLoader(getActivity(), msg.getId(), token, sinceId, null);
                case OLD_REPOST_LOADER_ID:
                    String maxId = null;

                    if (repostList.getSize() > 0) {
                        maxId = repostList.getItemList().get(repostList.getSize() - 1).getId();
                    }

                    return new RepostByIdMsgLoader(getActivity(), msg.getId(), token, null, maxId);
            }

            return null;
        }

        @Override
        public void onLoadFinished(Loader<AsyncTaskLoaderResult<RepostListBean>> loader, AsyncTaskLoaderResult<RepostListBean> result) {

            RepostListBean data = result != null ? result.data : null;
            WeiboException exception = result != null ? result.exception : null;
            Bundle args = result != null ? result.args : null;

            if (data != null) {
                Utility.buildTabCount(repostTab, getString(R.string.repost), data.getTotal_number());
            }

            switch (loader.getId()) {
                case NEW_REPOST_LOADER_ID:

                    if (Utility.isAllNotNull(exception)) {
                        Toast.makeText(getActivity(), exception.getError(), Toast.LENGTH_SHORT).show();
                    } else {
                        if (data != null && data.getSize() > 0) {
                            repostList.replaceAll(data);
                            adapter.notifyDataSetChanged();

                        }
                    }
                    break;
                case OLD_REPOST_LOADER_ID:

                    if (Utility.isAllNotNull(exception)) {
                        Toast.makeText(getActivity(), exception.getError(), Toast.LENGTH_SHORT).show();
                    } else {
                        repostList.addOldData(data);
                        adapter.notifyDataSetChanged();
                    }
                    break;
            }
            getLoaderManager().destroyLoader(loader.getId());
        }

        @Override
        public void onLoaderReset(Loader<AsyncTaskLoaderResult<RepostListBean>> loader) {

        }
    };

}
