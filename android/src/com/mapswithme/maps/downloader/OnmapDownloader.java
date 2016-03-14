package com.mapswithme.maps.downloader;

import android.location.Location;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

import com.mapswithme.maps.MwmActivity;
import com.mapswithme.maps.R;
import com.mapswithme.maps.background.Notifier;
import com.mapswithme.maps.location.LocationHelper;
import com.mapswithme.maps.routing.RoutingController;
import com.mapswithme.maps.widget.WheelProgressView;
import com.mapswithme.util.Config;
import com.mapswithme.util.ConnectionState;
import com.mapswithme.util.StringUtils;
import com.mapswithme.util.UiUtils;
import com.mapswithme.util.statistics.Statistics;

public class OnmapDownloader implements MwmActivity.LeftAnimationTrackListener
{
  private static boolean sAutodownloadLocked;

  private final MwmActivity mActivity;
  private final View mFrame;
  private final TextView mParent;
  private final TextView mTitle;
  private final TextView mSize;
  private final WheelProgressView mProgress;
  private final Button mButton;

  private int mStorageSubscriptionSlot;

  private CountryItem mCurrentCountry;

  private final MapManager.StorageCallback mStorageCallback = new MapManager.StorageCallback()
  {
    @Override
    public void onStatusChanged(List<MapManager.StorageCallbackData> data)
    {
      if (mCurrentCountry == null)
        return;

      for (MapManager.StorageCallbackData item : data)
      {
        if (!item.isLeafNode)
          continue;

        if (item.newStatus == CountryItem.STATUS_FAILED)
          MapManager.showError(mActivity, item);

        if (mCurrentCountry.id.equals(item.countryId))
        {
          mCurrentCountry.update();
          updateState(false);

          return;
        }
      }
    }

    @Override
    public void onProgress(String countryId, long localSize, long remoteSize)
    {
      if (mCurrentCountry != null && mCurrentCountry.id.equals(countryId))
      {
        mCurrentCountry.update();
        updateState(false);
      }
    }
  };

  private final MapManager.CurrentCountryChangedListener mCountryChangedListener = new MapManager.CurrentCountryChangedListener()
  {
    @Override
    public void onCurrentCountryChanged(String countryId)
    {
      mCurrentCountry = (TextUtils.isEmpty(countryId) ? null : CountryItem.fill(countryId));
      updateState(true);
    }
  };

  public void updateState(boolean shouldAutoDownload)
  {
    boolean showFrame = (mCurrentCountry != null &&
                         !RoutingController.get().isNavigating());
    if (showFrame)
    {
      boolean enqueued = (mCurrentCountry.status == CountryItem.STATUS_ENQUEUED);
      boolean progress = (mCurrentCountry.status == CountryItem.STATUS_PROGRESS);
      boolean failed = (mCurrentCountry.status == CountryItem.STATUS_FAILED);

      showFrame = (enqueued || progress || failed ||
                   mCurrentCountry.status == CountryItem.STATUS_DOWNLOADABLE);

      if (showFrame)
      {
        boolean hasParent = !TextUtils.isEmpty(mCurrentCountry.parentName);

        UiUtils.showIf(progress || enqueued, mProgress);
        UiUtils.showIf(!progress && !enqueued, mButton);
        UiUtils.showIf(hasParent, mParent);

        if (hasParent)
          mParent.setText(mCurrentCountry.parentName);

        mTitle.setText(mCurrentCountry.name);

        if (progress)
        {
          mSize.setText(StringUtils.getFileSizeString(mCurrentCountry.totalSize));
          mProgress.setProgress((int) (mCurrentCountry.progress * 100L / mCurrentCountry.totalSize));
        }
        else
        {
          if (enqueued)
          {
            mSize.setText(R.string.downloader_queued);
            mProgress.setProgress(0);
          }
          else
          {
            if (shouldAutoDownload &&
                !sAutodownloadLocked &&
                !failed &&
                !MapManager.nativeIsLegacyMode() &&
                Config.isAutodownloadMaps() &&
                ConnectionState.isWifiConnected())
            {
              Location loc = LocationHelper.INSTANCE.getLastLocation();
              if (loc != null)
              {
                String country = MapManager.nativeFindCountry(loc.getLatitude(), loc.getLongitude());
                if (TextUtils.equals(mCurrentCountry.id, country))
                {
                  MapManager.nativeDownload(mCurrentCountry.id);

                  Statistics.INSTANCE.trackEvent(Statistics.EventName.DOWNLOADER_ACTION,
                                                 Statistics.params().add(Statistics.EventParam.ACTION, "download")
                                                                    .add(Statistics.EventParam.FROM, "map")
                                                                    .add("is_auto", "true")
                                                                    .add("scenario", "download"));
                }
              }
            }

            mButton.setText(failed ? R.string.downloader_retry
                                   : R.string.download);
          }
        }
      }
    }

    UiUtils.showIf(showFrame, mFrame);
  }

  public OnmapDownloader(MwmActivity activity)
  {
    mActivity = activity;
    mFrame = activity.findViewById(R.id.onmap_downloader);
    mParent = (TextView)mFrame.findViewById(R.id.downloader_parent);
    mTitle = (TextView)mFrame.findViewById(R.id.downloader_title);
    mSize = (TextView)mFrame.findViewById(R.id.downloader_size);

    View controls = mFrame.findViewById(R.id.downloader_controls_frame);
    mProgress = (WheelProgressView) controls.findViewById(R.id.downloader_progress);
    mButton = (Button) controls.findViewById(R.id.downloader_button);

    mProgress.setOnClickListener(new View.OnClickListener()
    {
      @Override
      public void onClick(View v)
      {
        MapManager.nativeCancel(mCurrentCountry.id);
        Statistics.INSTANCE.trackEvent(Statistics.EventName.DOWNLOADER_CANCEL,
                                       Statistics.params().add(Statistics.EventParam.FROM, "map"));
        sAutodownloadLocked = true;
      }
    });

    mButton.setOnClickListener(new View.OnClickListener()
    {
      @Override
      public void onClick(View v)
      {
        if (MapManager.nativeIsLegacyMode())
        {
          mActivity.showDownloader(false);
          return;
        }

        boolean retry = (mCurrentCountry.status == CountryItem.STATUS_FAILED);
        if (retry)
        {
          Notifier.cancelDownloadFailed();
          MapManager.nativeRetry(mCurrentCountry.id);
        }
        else
          MapManager.nativeDownload(mCurrentCountry.id);

        Statistics.INSTANCE.trackEvent(Statistics.EventName.DOWNLOADER_ACTION,
                                       Statistics.params().add(Statistics.EventParam.ACTION, (retry ? "retry" : "download"))
                                                          .add(Statistics.EventParam.FROM, "map")
                                                          .add("is_auto", "false")
                                                          .add("scenario", "download"));
      }
    });

    UiUtils.updateAccentButton(mButton);
  }

  @Override
  public void onTrackStarted(boolean collapsed) {}

  @Override
  public void onTrackFinished(boolean collapsed) {}

  @Override
  public void onTrackLeftAnimation(float offset)
  {
    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)mFrame.getLayoutParams();
    lp.leftMargin = (int)offset;
    mFrame.setLayoutParams(lp);
  }

  public void onPause()
  {
    if (mStorageSubscriptionSlot > 0)
    {
      MapManager.nativeUnsubscribe(mStorageSubscriptionSlot);
      mStorageSubscriptionSlot = 0;
    }

    MapManager.nativeUnsubscribeOnCountryChanged();
  }

  public void onResume()
  {
    mStorageSubscriptionSlot = MapManager.nativeSubscribe(mStorageCallback);
    MapManager.nativeSubscribeOnCountryChanged(mCountryChangedListener);
  }
}