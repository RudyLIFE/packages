package com.mediatek.gallery3d.ext;

/**
 * MoviePlayer extension functions interface
 */
public interface IMoviePlayer {
    /**
     * add new bookmark Uri.
     */
    void addBookmark();
    /**
     * start current item and stop playing video.
     * @param item
     */
    void startNextVideo(IMovieItem item);
    /**
     * Loop current video.
     * @param loop
     */
    void setLoop(boolean loop);
    /**
     * Loop current video or not
     * @return
     */
    boolean getLoop();
    /**
     * Show video details.
     */
    void showDetail();
    /**
     * Can stop current video or not.
     * @return
     */
    boolean canStop();
    /**
     * Stop current video.
     */
    void stopVideo();
    /**
     * show current SubtitleView.
     */
    void showSubtitleViewSetDialog();   

}
