// Original Cosmotune bridge to Apple's public MediaPlayer APIs. No upstream app source is included.
#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>
#include <stdint.h>

typedef void (*CosmoMediaCommand)(int command, double position);
static CosmoMediaCommand commandCallback;
static NSMutableArray *commandTargets;
static NSMutableDictionary *currentInfo;
static int64_t currentGeneration;
static double anchorTime, anchorPosition, anchorRate, trackDuration;

static void installCommand(MPRemoteCommand *command, int code) {
    id target = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        if (!commandCallback || !currentInfo) return MPRemoteCommandHandlerStatusNoSuchContent;
        double position = [event isKindOfClass:[MPChangePlaybackPositionCommandEvent class]]
            ? [(MPChangePlaybackPositionCommandEvent *)event positionTime] : 0;
        commandCallback(code, position);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [commandTargets addObject:@[command, target]];
}

void cosmo_media_start(CosmoMediaCommand callback) {
    dispatch_async(dispatch_get_main_queue(), ^{
        commandCallback = callback;
        if (commandTargets) return;
        commandTargets = [NSMutableArray array];
        MPRemoteCommandCenter *center = MPRemoteCommandCenter.sharedCommandCenter;
        installCommand(center.playCommand, 0);
        installCommand(center.pauseCommand, 1);
        installCommand(center.togglePlayPauseCommand, 2);
        installCommand(center.nextTrackCommand, 3);
        installCommand(center.previousTrackCommand, 4);
        installCommand(center.changePlaybackPositionCommand, 5);
        installCommand(center.stopCommand, 6);
    });
}

void cosmo_media_update(int64_t generation, const char *title, const char *artist, const char *album,
    double position, double duration, double rate, int playing) {
    NSString *name = title ? [NSString stringWithUTF8String:title] : @"";
    NSString *credit = artist ? [NSString stringWithUTF8String:artist] : @"";
    NSString *release = album ? [NSString stringWithUTF8String:album] : @"";
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!commandCallback) return;
        MPNowPlayingInfoCenter *center = MPNowPlayingInfoCenter.defaultCenter;
        if (name.length == 0) {
            currentInfo = nil;
            currentGeneration = generation;
            center.nowPlayingInfo = nil;
            center.playbackState = MPNowPlayingPlaybackStateStopped;
            for (NSArray *entry in commandTargets) [(MPRemoteCommand *)entry[0] setEnabled:NO];
            return;
        }
        MPMediaItemArtwork *art = generation == currentGeneration ? currentInfo[MPMediaItemPropertyArtwork] : nil;
        currentGeneration = generation;
        anchorTime = NSProcessInfo.processInfo.systemUptime;
        anchorPosition = position;
        anchorRate = rate;
        trackDuration = duration;
        currentInfo = [@{
            MPMediaItemPropertyTitle: name,
            MPMediaItemPropertyArtist: credit,
            MPMediaItemPropertyAlbumTitle: release,
            MPMediaItemPropertyPlaybackDuration: @(duration),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: @(position),
            MPNowPlayingInfoPropertyPlaybackRate: @(rate),
            MPNowPlayingInfoPropertyDefaultPlaybackRate: @1.0,
            MPNowPlayingInfoPropertyMediaType: @(MPNowPlayingInfoMediaTypeAudio)
        } mutableCopy];
        if (art) currentInfo[MPMediaItemPropertyArtwork] = art;
        center.nowPlayingInfo = currentInfo;
        center.playbackState = playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
        for (NSArray *entry in commandTargets) [(MPRemoteCommand *)entry[0] setEnabled:YES];
        MPRemoteCommandCenter.sharedCommandCenter.changePlaybackPositionCommand.enabled = duration > 0;
    });
}

void cosmo_media_artwork(int64_t generation, const unsigned char *bytes, int64_t length) {
    if (!bytes || length <= 0 || length > 4 * 1024 * 1024) return;
    NSData *data = [NSData dataWithBytes:bytes length:(NSUInteger)length];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!commandCallback || !currentInfo || generation != currentGeneration) return;
        NSImage *image = [[NSImage alloc] initWithData:data];
        if (!image || image.size.width <= 0 || image.size.height <= 0) return;
        MPMediaItemArtwork *art = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
            requestHandler:^NSImage *(CGSize size) { return image; }];
        currentInfo[MPMediaItemPropertyArtwork] = art;
        // Do not republish an old elapsed time just because artwork arrived late.
        NSDictionary *live = MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo;
        NSMutableDictionary *updated = [live mutableCopy] ?: [currentInfo mutableCopy];
        updated[MPMediaItemPropertyArtwork] = art;
        double position = anchorPosition + MAX(0, NSProcessInfo.processInfo.systemUptime - anchorTime) * anchorRate;
        if (trackDuration > 0) position = MIN(position, trackDuration);
        updated[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(MAX(0, position));
        anchorPosition = position;
        anchorTime = NSProcessInfo.processInfo.systemUptime;
        MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = updated;
    });
}

void cosmo_media_stop(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        commandCallback = NULL;
        for (NSArray *entry in commandTargets) {
            [(MPRemoteCommand *)entry[0] removeTarget:entry[1]];
            [(MPRemoteCommand *)entry[0] setEnabled:NO];
        }
        commandTargets = nil;
        currentInfo = nil;
        MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = nil;
        MPNowPlayingInfoCenter.defaultCenter.playbackState = MPNowPlayingPlaybackStateStopped;
    });
}
