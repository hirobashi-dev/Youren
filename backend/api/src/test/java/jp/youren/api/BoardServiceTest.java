package jp.youren.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import jp.youren.database.mapper.BoardMapper;
import jp.youren.database.mapper.MediaMapper;
import org.junit.jupiter.api.Test;

// 验证本人编辑的边界：未发送图片字段只改文字，必须保留既有图片关联。
class BoardServiceTest {
  @Test
  void editPostWithoutMediaFieldPreservesExistingImages() {
    var mapper = mock(BoardMapper.class);
    var id = UUID.randomUUID();
    var owner = UUID.randomUUID();
    var updated =
        new BoardMapper.PostRow(
            id,
            UUID.randomUUID(),
            "guest",
            null,
            null,
            "新标题",
            "新正文",
            "general",
            null,
            "pending",
            null,
            null,
            2);
    when(mapper.updateOwnPost(
            eq(id),
            eq(owner),
            eq(1),
            eq("新标题"),
            eq("新正文"),
            eq("general"),
            isNull(),
            any(OffsetDateTime.class)))
        .thenReturn(1);
    when(mapper.findOwnPost(id, owner)).thenReturn(updated);
    var service = new BoardService(mapper, mock(MediaMapper.class), null, Clock.systemUTC(), 86400);

    var result =
        service.editPost(owner, id, 1, "新标题", "新正文", "general", null, java.util.List.of(), null);

    assertEquals(2, result.version());
    verify(mapper, never()).deletePostMedia(id);
    verify(mapper, never()).insertPostMedia(any(), any(), anyInt(), any());
  }
}
