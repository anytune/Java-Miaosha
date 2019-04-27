package com.liushaoming.jseckill.backend.service.impl;

import com.liushaoming.jseckill.backend.dao.SeckillDAO;
import com.liushaoming.jseckill.backend.dao.SuccessKilledDAO;
import com.liushaoming.jseckill.backend.dao.cache.RedisDAO;
import com.liushaoming.jseckill.backend.dto.Exposer;
import com.liushaoming.jseckill.backend.dto.SeckillExecution;
import com.liushaoming.jseckill.backend.entity.Seckill;
import com.liushaoming.jseckill.backend.entity.SuccessKilled;
import com.liushaoming.jseckill.backend.enums.SeckillStateEnum;
import com.liushaoming.jseckill.backend.exception.SeckillException;
import com.liushaoming.jseckill.backend.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.List;

/**
 * @author liushaoming
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    //注入Service依赖
    @Autowired
    private SeckillDAO seckillDAO;

    @Autowired
    private SuccessKilledDAO successKilledDAO;

    @Autowired
    private RedisDAO redisDAO;

    //md5盐值字符串,用于混淆MD5
    private final String salt = "aksksks*&&^%%aaaa&^^%%*";

    @Override
    public List<Seckill> getSeckillList() {
        return seckillDAO.queryAll(0, 4);
    }

    @Override
    public Seckill getById(long seckillId) {
        return seckillDAO.queryById(seckillId);
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        // 优化点:缓存优化:超时的基础上维护一致性
        //1.访问Redis
        Seckill seckill = redisDAO.getSeckill(seckillId);
        if (seckill == null) {
            //2.访问数据库
            seckill = seckillDAO.queryById(seckillId);
            if (seckill == null) {
                return new Exposer(false, seckillId);
            } else {
                //3.存入Redis
                redisDAO.putSeckill(seckill);
            }
        }

        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        //系统当前时间
        Date nowTime = new Date();
        if (nowTime.getTime() < startTime.getTime()
                || nowTime.getTime() > endTime.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(),
                    endTime.getTime());
        }
        //转化特定字符串的过程，不可逆
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + salt;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    @Override
    @Transactional
    /**
     * 先插入秒杀记录再减库存
     */
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5)
            throws SeckillException {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            logger.info("seckill data rewrite!!!. seckillId={},userPhone={}", seckillId, userPhone);
            throw new SeckillException(SeckillStateEnum.DATA_REWRITE);
        }
        //执行秒杀逻辑:减库存 + 记录购买行为
        Date nowTime = new Date();

        try {
            //插入秒杀记录(记录购买行为)
            //这处， seckill_record的id等于这个特定id的行被启用了行锁,   但是其他的事务可以insert另外一行， 不会阻止其他事务里对这个表的insert操作
            int insertCount = successKilledDAO.insertSuccessKilled(seckillId, userPhone);
            //唯一:seckillId,userPhone
            if (insertCount <= 0) {
                //重复秒杀
                logger.info("seckill repeated. seckillId={},userPhone={}", seckillId, userPhone);
                throw new SeckillException(SeckillStateEnum.REPEAT_KILL);
            } else {
                //减库存,热点商品竞争
                // reduceNumber是update操作，开启作用在表seckill上的行锁
                Seckill currentSeckill = seckillDAO.queryById(seckillId);
                boolean validTime = false;
                if (currentSeckill != null) {
                    long nowStamp = nowTime.getTime();
                    if (nowStamp > currentSeckill.getStartTime().getTime() && nowStamp < currentSeckill.getEndTime().getTime()
                            && currentSeckill.getNumber() > 0 && currentSeckill.getVersion() > 0) {
                        validTime = true;
                    }
                }

                if (validTime) {
                    long oldVersion = currentSeckill.getVersion();
                    // update操作开始，表seckill的seckill_id等于seckillId的行被启用了行锁,   其他的事务无法update这一行， 可以update其他行
                    int updateCount = seckillDAO.reduceNumber(seckillId, oldVersion, oldVersion + 1);
                    if (updateCount <= 0) {
                        //没有更新到记录，秒杀结束,rollback
//                        throw new SeckillCloseException("seckill is closed");
                        //
                        logger.error("数据库并发错误", "数据库并发错误");
                        throw new SeckillException(SeckillStateEnum.CONCURRENCY_ERROR);
                    } else {
                        //秒杀成功 commit
                        SuccessKilled successKilled = successKilledDAO.queryByIdWithSeckill(seckillId, userPhone);
                        logger.info("seckill SUCCESS->>>. seckillId={},userPhone={}", seckillId, userPhone);
                        return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
                        //return后，事务结束，关闭作用在表seckill上的行锁
                        // update结束，行锁被取消  。reduceNumber()被执行前后数据行被锁定, 其他的事务无法写这一行。
                    }
                } else {
                    throw new SeckillException(SeckillStateEnum.END);
                }
            }
        } catch (SeckillException e1) {
            throw e1;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            //  所有编译期异常 转化为运行期异常
            throw new SeckillException(SeckillStateEnum.INNER_ERROR);
        }
    }
}
