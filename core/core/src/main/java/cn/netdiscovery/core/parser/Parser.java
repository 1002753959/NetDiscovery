package cn.netdiscovery.core.parser;

import cn.netdiscovery.core.domain.Page;

/**
 * Created by tony on 2017/12/22.
 */
public interface Parser {

    /**
     * 对Page进行解析将结果放到Page里的resultItems
     * @param page
     */
    void process(Page page);
}
